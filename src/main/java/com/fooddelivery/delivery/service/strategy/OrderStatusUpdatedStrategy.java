package com.fooddelivery.delivery.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fooddelivery.common.constants.EventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.delivery.service.LogisticsDispatchService;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusUpdatedStrategy implements DeliveryEventStrategy {

    private final StringRedisTemplate redisTemplate;
    private final LogisticsDispatchService logisticsDispatchService;
    private final ObjectMapper objectMapper;

    @Override
    public void process(JsonNode root, String eventType) throws Exception {
        String orderId = root.path("orderId").asText(null);
        String status = null;

        if (EventType.ORDER_STATUS_UPDATED.name().equals(eventType)) {
            status = root.path("status").asText(null);
        } else if (EventType.ORDER_READY.name().equals(eventType)) {
            status = com.fooddelivery.common.enums.OrderStatus.READY_FOR_PICKUP.name();
        } else if (EventType.ORDER_PREPARING.name().equals(eventType)) {
            status = com.fooddelivery.common.enums.OrderStatus.PREPARING.name();
        } else if (EventType.ORDER_ACCEPTED.name().equals(eventType)) {
            status = com.fooddelivery.common.enums.OrderStatus.ACCEPTED.name();
        }

        if (orderId != null && status != null) {
            String currentStatusStr = redisTemplate.opsForValue().get(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_RESTAURANT_STATUS + orderId);
            try {
                com.fooddelivery.common.enums.OrderStatus newStatus = com.fooddelivery.common.enums.OrderStatus.valueOf(status);
                if (currentStatusStr != null) {
                    com.fooddelivery.common.enums.OrderStatus currentStatus = com.fooddelivery.common.enums.OrderStatus.valueOf(currentStatusStr);
                    if (newStatus.getSequence() <= currentStatus.getSequence() && newStatus != currentStatus) {
                        log.warn("Ignoring backward transition for order {}. Current: {}, New: {}", orderId, currentStatus, newStatus);
                        return;
                    }
                }
            } catch (IllegalArgumentException e) {
                log.warn("Invalid status enum: {}", status);
            }

            log.info("Received {} for order {} setting restaurantStatus to {}", eventType, orderId, status);
            redisTemplate.opsForValue().set(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_RESTAURANT_STATUS + orderId, status, java.time.Duration.ofHours(24));
            
            // Publish the new status to a Pub/Sub channel for live updates to the rider
            String channel = "restaurant-status:order:" + orderId;
            redisTemplate.convertAndSend(channel, status);
            log.info("Published restaurant status {} to channel {}", status, channel);

            if (com.fooddelivery.common.enums.OrderStatus.READY_FOR_PICKUP.name().equals(status)) {
                // We check if it is in the queue by looking up its score. If it has a score, it's in the queue.
                Double score = redisTemplate.opsForZSet().score("delayed_dispatch_queue", orderId);
                if (score != null) {
                    // Try to acquire the processing lock for this order
                    String lockKey = "dispatch_processing_lock:" + orderId;
                    Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", java.time.Duration.ofSeconds(30));
                    if (Boolean.TRUE.equals(acquired)) {
                        log.info("Order {} is ready early! Acquired lock, triggering immediate dispatch.", orderId);
                        String payload = redisTemplate.opsForValue().get(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_PAYLOAD + orderId);
                        if (payload != null) {
                            JsonNode payloadRoot = objectMapper.readTree(payload);
                            double lat = payloadRoot.path("restaurantLat").asDouble(0.0);
                            double lng = payloadRoot.path("restaurantLng").asDouble(0.0);
                            double deliveryLat = payloadRoot.path("deliveryLat").asDouble(0.0);
                            double deliveryLng = payloadRoot.path("deliveryLng").asDouble(0.0);
                            String deliveryAddress = payloadRoot.path("deliveryAddress").asText("");
                            
                            java.util.Set<String> rejectedDrivers = redisTemplate.opsForSet().members(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_REJECTED_DRIVERS + orderId);
                            java.util.List<String> excludedDriverIds = rejectedDrivers != null ? new java.util.ArrayList<>(rejectedDrivers) : null;
                            
                            logisticsDispatchService.dispatchNearestDriver(lat, lng, deliveryLat, deliveryLng, deliveryAddress, java.util.UUID.fromString(orderId), excludedDriverIds);
                        }
                        
                        // Remove from delayed queue ONLY after successful dispatch
                        redisTemplate.opsForZSet().remove("delayed_dispatch_queue", orderId);
                    }
                }
            }
        } else {
            log.warn("Invalid event received: {}", root);
        }
    }

    @Override
    public List<String> getEventTypes() {
        return Arrays.asList(
            EventType.ORDER_STATUS_UPDATED.name(),
            EventType.ORDER_READY.name(),
            EventType.ORDER_PREPARING.name(),
            EventType.ORDER_ACCEPTED.name()
        );
    }
}
