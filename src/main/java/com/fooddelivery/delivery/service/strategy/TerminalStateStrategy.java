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
        
        // Cleanup the Redis dispatch payload on any terminal/successful state
        redisTemplate.delete("order:dispatchPayload:" + orderId);
        redisTemplate.opsForZSet().remove("delayed_dispatch_queue", orderId.toString());

        if (EventType.ORDER_CANCELLED.name().equals(eventType) || EventType.DELIVERY_FAILED.name().equals(eventType) || EventType.ORDER_CANCELLED_BY_RESTAURANT.name().equals(eventType) || EventType.ORDER_CANCELLED_BY_CUSTOMER.name().equals(eventType) || EventType.ORDER_REJECTED.name().equals(eventType) || EventType.ORDER_DELAY_REJECTED.name().equals(eventType)) {
            if (driverId == null || driverId.isEmpty()) {
                driverId = redisTemplate.opsForValue().get("order:driver:lock:" + orderId);
            }
            if (driverId == null || driverId.isEmpty()) {
                driverId = redisTemplate.opsForValue().get("order:ping:pending:" + orderId);
            }
            
            // Clean up any pending pings to prevent timeout poller from penalizing the driver
            redisTemplate.delete("order:ping:pending:" + orderId);
            if (driverId != null && !driverId.isEmpty()) {
                redisTemplate.delete("driver:pending_ping:" + driverId);
            }
            redisTemplate.opsForZSet().remove("order:ping:timeouts", orderId.toString());
            
            // PREVENT any in-flight ping from being successfully accepted later
            redisTemplate.opsForValue().set("order:driver:lock:" + orderId, com.fooddelivery.common.enums.OrderStatus.CANCELLED.name(), java.time.Duration.ofHours(24));
            
            if (driverId != null && !driverId.isEmpty() && !driverId.equals(com.fooddelivery.common.enums.OrderStatus.CANCELLED.name()) && !driverId.equals("locked")) {
                logisticsDispatchService.releaseDriverLock(driverId);
                
                // Reset driver status in DB
                final String finalDriverId = driverId;
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        DeliveryExecutive executive = executiveRepository.findLockedById(UUID.fromString(finalDriverId)).orElse(null);
                        if (executive != null && executive.getStatus() == DeliveryExecutiveStatus.ON_DELIVERY) {
                            executive.setStatus(DeliveryExecutiveStatus.ONLINE);
                            executive.setUpdatedAt(java.time.LocalDateTime.now());
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
                } catch (Exception ex) {
                    log.error("Failed to reset driver status in DB", ex);
                }
            }
            // we delete the dispatch lock since the order is cancelled
            redisTemplate.delete("order:dispatch:lock:" + orderId);
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
                EventType.ORDER_REJECTED.name(), 
                EventType.ORDER_DELAY_REJECTED.name()
        );
    }
}
