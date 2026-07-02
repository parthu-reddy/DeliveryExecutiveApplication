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

    @Override
    public void process(JsonNode root, String eventType) throws Exception {
        UUID orderId = UUID.fromString(root.path("orderId").asText());
        String driverId = root.path("driverId").asText(null);
        log.info("Delivery Application received {} for order {}. Cleaning up pending dispatches.", eventType, orderId);
        
        // Cleanup the Redis dispatch payload on any terminal/successful state
        redisTemplate.delete("order:dispatchPayload:" + orderId);
        redisTemplate.opsForZSet().remove("delayed_dispatch_queue", orderId.toString());

        if (EventType.ORDER_CANCELLED.equals(eventType) || EventType.DELIVERY_FAILED.equals(eventType) || EventType.ORDER_CANCELLED_BY_RESTAURANT.equals(eventType) || EventType.ORDER_REJECTED.equals(eventType) || EventType.ORDER_DELAY_REJECTED.equals(eventType)) {
            if (driverId == null || driverId.isEmpty()) {
                driverId = redisTemplate.opsForValue().get("order:driver:lock:" + orderId);
            }
            
            // PREVENT any in-flight ping from being successfully accepted later
            redisTemplate.opsForValue().set("order:driver:lock:" + orderId, "CANCELLED", java.time.Duration.ofHours(24));
            
            if (driverId != null && !driverId.isEmpty() && !driverId.equals("CANCELLED") && !driverId.equals("locked")) {
                logisticsDispatchService.releaseDriverLock(driverId);
                
                // Reset driver status in DB
                try {
                    DeliveryExecutive executive = executiveRepository.findLockedById(UUID.fromString(driverId)).orElse(null);
                    if (executive != null && executive.getStatus() == DeliveryExecutiveStatus.ON_DELIVERY) {
                        executive.setStatus(DeliveryExecutiveStatus.ONLINE);
                        executive.setUpdatedAt(java.time.LocalDateTime.now());
                        executiveRepository.save(executive);
                        log.info("Reset driver {} to ONLINE after order {} was cancelled.", driverId, orderId);
                    }
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
                EventType.DRIVER_ASSIGNED, 
                EventType.DISPATCH_FAILED, 
                EventType.ORDER_CANCELLED, 
                EventType.DELIVERY_FAILED, 
                EventType.ORDER_CANCELLED_BY_RESTAURANT, 
                EventType.ORDER_REJECTED, 
                EventType.ORDER_DELAY_REJECTED
        );
    }
}
