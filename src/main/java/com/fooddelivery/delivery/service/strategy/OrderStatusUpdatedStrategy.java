package com.fooddelivery.delivery.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fooddelivery.common.constants.EventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusUpdatedStrategy implements DeliveryEventStrategy {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void process(JsonNode root, String eventType) throws Exception {
        String orderId = root.path("orderId").asText(null);
        String status = root.path("status").asText(null);

        if (orderId != null && status != null) {
            log.info("Received ORDER_STATUS_UPDATED for order {} with status {}", orderId, status);
            redisTemplate.opsForValue().set("order:restaurantStatus:" + orderId, status);
        } else {
            log.warn("Invalid ORDER_STATUS_UPDATED event received: {}", root);
        }
    }

    @Override
    public List<String> getEventTypes() {
        return Collections.singletonList(EventType.ORDER_STATUS_UPDATED.name());
    }
}
