package com.fooddelivery.delivery.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fooddelivery.common.constants.EventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusUpdatedStrategy implements DeliveryEventStrategy {

    private final StringRedisTemplate redisTemplate;

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
