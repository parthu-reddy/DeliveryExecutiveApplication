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
            log.info("Received {} for order {} setting restaurantStatus to {}", eventType, orderId, status);
            redisTemplate.opsForValue().set("order:restaurantStatus:" + orderId, status);
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
