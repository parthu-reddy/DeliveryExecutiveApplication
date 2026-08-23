package com.fooddelivery.delivery.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.delivery.service.LogisticsDispatchService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
@lombok.extern.slf4j.Slf4j
public class OrderDriverRejectedStrategy implements DeliveryEventStrategy {
private final LogisticsDispatchService logisticsDispatchService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void process(JsonNode root, String eventType) throws Exception {
        UUID orderId = UUID.fromString(root.path("orderId").asText());
        log.info("Delivery Application received ORDER_DRIVER_REJECTED for order {}. Fetching original payload to retry dispatch...", orderId);
        // Guard: if the order was cancelled/terminal, the dispatch lock is set to "CANCELLED" — skip redispatch
        String dispatchLock = redisTemplate.opsForValue().get(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_LOCK + orderId);
        if ("CANCELLED".equals(dispatchLock)) {
            log.info("Order {} dispatch lock is CANCELLED. Skipping redispatch.", orderId);
            return;
        }
        String cachedPayload = redisTemplate.opsForValue().get(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_PAYLOAD + orderId);
        if (cachedPayload != null) {
            JsonNode cachedRoot = objectMapper.readTree(cachedPayload);
            double lat = cachedRoot.path("restaurantLat").asDouble(0.0);
            double lng = cachedRoot.path("restaurantLng").asDouble(0.0);
            double deliveryLat = cachedRoot.path("deliveryLat").asDouble(0.0);
            double deliveryLng = cachedRoot.path("deliveryLng").asDouble(0.0);
            String deliveryAddress = cachedRoot.path("deliveryAddress").asText("");
            if (lat != 0.0 && lng != 0.0) {
                Long failedCycles = redisTemplate.opsForValue().increment("order:dispatch_failed_cycles:" + orderId);
                redisTemplate.expire("order:dispatch_failed_cycles:" + orderId, java.time.Duration.ofHours(2));
                long delayMs = 10000; // 10-second cooldown between batch re-dispatches
                long dispatchAt = System.currentTimeMillis() + delayMs;
                log.info("Queueing order {} for delayed dispatch retry via poller (Cycle: {}). Will dispatch at {} ({}ms delay).", orderId, failedCycles, dispatchAt, delayMs);
                redisTemplate.opsForZSet().add("delayed_dispatch_queue", orderId.toString(), dispatchAt);
            } else {
                log.warn("Cached payload for order {} has missing coordinates. Cannot redispatch.", orderId);
            }
        } else {
            log.warn("No cached dispatch payload found for order {}. Cannot redispatch driver.", orderId);
        }
    }

    @Override
    public List<String> getEventTypes() {
        return Collections.singletonList(EventType.ORDER_DRIVER_REJECTED.name());
    }

public OrderDriverRejectedStrategy(final LogisticsDispatchService logisticsDispatchService, final StringRedisTemplate redisTemplate, final ObjectMapper objectMapper) {
        this.logisticsDispatchService = logisticsDispatchService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }
}
