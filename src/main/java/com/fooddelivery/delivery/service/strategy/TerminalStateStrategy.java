package com.fooddelivery.delivery.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.LogisticsDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TerminalStateStrategy implements DeliveryEventStrategy {

    private final StringRedisTemplate redisTemplate;
    private final IDeliveryExecutiveRepository executiveRepository;
    private final LogisticsDispatchService logisticsDispatchService;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @Override
    public void process(JsonNode root, String eventType) throws Exception {
        UUID orderId = UUID.fromString(root.path("orderId").asText());
        String driverId = root.path("driverId").asText(null);
        log.info("Delivery Application received {} for order {}. Cleaning up pending dispatches.", eventType, orderId);
        
        // Only delete dispatch payload for cancellation/failure events — NOT for DRIVER_ASSIGNED.
        // OutForDeliveryStateStrategy reads order:dispatchPayload to validate pickup OTP,
        // and DeliveredStateStrategy reads it for delivery OTP validation.
        // Cleanup happens in DeliveredStateStrategy.postProcess() / DeliveryFailedStateStrategy.postProcess().
        if (!EventType.DRIVER_ASSIGNED.name().equals(eventType) && !EventType.DISPATCH_FAILED.name().equals(eventType)) {
            redisTemplate.delete(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_PAYLOAD + orderId);
        }
        redisTemplate.delete(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_REJECTED_DRIVERS + orderId);
        
        if (!EventType.DISPATCH_FAILED.name().equals(eventType)) {
            redisTemplate.delete("order:dispatch_failed_cycles:" + orderId);
        }
        
        redisTemplate.opsForZSet().remove("delayed_dispatch_queue", orderId.toString());

        if (EventType.ORDER_CANCELLED.name().equals(eventType) || EventType.DELIVERY_FAILED.name().equals(eventType) || EventType.ORDER_CANCELLED_BY_RESTAURANT.name().equals(eventType) || EventType.ORDER_CANCELLED_BY_CUSTOMER.name().equals(eventType) || EventType.ORDER_CANCELLED_BY_ADMIN.name().equals(eventType) || EventType.ORDER_REJECTED.name().equals(eventType) || EventType.ORDER_DELAY_REJECTED.name().equals(eventType) || EventType.MANUAL_INTERVENTION_REQUIRED.name().equals(eventType)) {
            if (driverId == null || driverId.isEmpty()) {
                driverId = redisTemplate.opsForValue().get(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId);
            }
            
            String strandedDriver = redisTemplate.opsForValue().get("order:driver:stranded:" + orderId);
            if (strandedDriver != null) {
                driverId = strandedDriver;
            }
            
            // Read ALL pending drivers BEFORE deleting the set, so we can clean up every driver's state
            java.util.Set<String> allPendingDrivers = redisTemplate.opsForSet().members(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderId);
            
            // Clean up any pending pings to prevent timeout poller from penalizing the driver
            redisTemplate.delete(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderId);
            
            // Clean up driver:pending_ping for ALL pinged drivers, not just the one in the event
            if (allPendingDrivers != null && !allPendingDrivers.isEmpty()) {
                for (String pendingDriverId : allPendingDrivers) {
                    redisTemplate.delete(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVER_PENDING_PING + pendingDriverId);
                }
                // If we didn't get a driverId from the event or lock, use one from the pending set
                if (driverId == null || driverId.isEmpty()) {
                    driverId = allPendingDrivers.iterator().next();
                }
            } else if (driverId != null && !driverId.isEmpty()) {
                redisTemplate.delete(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVER_PENDING_PING + driverId);
            }
            
            redisTemplate.opsForZSet().remove(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS, orderId.toString());
            
            // PREVENT any in-flight ping from being successfully accepted later
            redisTemplate.opsForValue().set(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId, com.fooddelivery.common.enums.OrderStatus.CANCELLED.name(), java.time.Duration.ofHours(24));
            
            if (driverId != null && !driverId.isEmpty() && !driverId.equals(com.fooddelivery.common.enums.OrderStatus.CANCELLED.name()) && !driverId.equals("locked")) {
                redisTemplate.opsForValue().set("order:driver:stranded:" + orderId, driverId, java.time.Duration.ofHours(24));
                
                try {
                    logisticsDispatchService.releaseDriverLock(driverId);
                } catch (Exception e) {
                    log.error("Failed to release driver lock for driver {} during terminal cleanup", driverId, e);
                }
                
                // Reset driver status in DB
                final String finalDriverId = driverId;
                transactionTemplate.executeWithoutResult(status -> {
                    DeliveryExecutive executive = executiveRepository.findLockedById(UUID.fromString(finalDriverId)).orElse(null);
                    if (executive != null && executive.getStatus() == DeliveryExecutiveStatus.ON_DELIVERY) {
                        executive.setStatus(DeliveryExecutiveStatus.ONLINE);
                        // updatedAt is auto-managed by @UpdateTimestamp
                        executiveRepository.save(executive);
                        log.info("Reset driver {} to ONLINE after order {} was cancelled.", finalDriverId, orderId);
                        
                        // Add back to available pool
                        try {
                            String key = "drivers:available:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;
                            redisTemplate.opsForSet().add(key, finalDriverId);
                        } catch (Exception e) {
                            log.error("Failed to add driver {} back to Redis pool", finalDriverId, e);
                        }
                    }
                });
                
                redisTemplate.delete("order:driver:stranded:" + orderId);
            }
            // we set the dispatch lock to CANCELLED to prevent new dispatch loops
            redisTemplate.opsForValue().set(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_LOCK + orderId, "CANCELLED", java.time.Duration.ofHours(24));
        }
        if (EventType.DISPATCH_FAILED.name().equals(eventType)) {
            // If dispatch failed (no drivers available), we should retry in 10 seconds.
            Long failedCycles = redisTemplate.opsForValue().increment("order:dispatch_failed_cycles:" + orderId);
            redisTemplate.expire("order:dispatch_failed_cycles:" + orderId, java.time.Duration.ofHours(2));
            log.info("Dispatch failed for order {} (Consecutive Failures: {}). Retrying in 10 seconds...", orderId, failedCycles);
            redisTemplate.opsForZSet().add("delayed_dispatch_queue", orderId.toString(), System.currentTimeMillis() + 10000);
        } else {
            // For ALL terminal events (excluding DISPATCH_FAILED), mark dispatch as complete
            // This prevents stale Kafka retries of ORDER_DRIVER_REJECTED from re-dispatching
            redisTemplate.opsForValue().set(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_LOCK + orderId, "CANCELLED", java.time.Duration.ofHours(24));
        }
    }

    @Override
    public List<String> getEventTypes() {
        return Arrays.asList(
                EventType.DRIVER_ASSIGNED.name(), 
                EventType.DISPATCH_FAILED.name(), 
                EventType.ORDER_CANCELLED.name(), 
                EventType.DELIVERY_FAILED.name(), 
                EventType.ORDER_CANCELLED_BY_RESTAURANT.name(), 
                EventType.ORDER_CANCELLED_BY_CUSTOMER.name(),
                EventType.ORDER_CANCELLED_BY_ADMIN.name(),
                EventType.ORDER_REJECTED.name(), 
                EventType.ORDER_DELAY_REJECTED.name(),
                EventType.MANUAL_INTERVENTION_REQUIRED.name()
        );
    }
}
