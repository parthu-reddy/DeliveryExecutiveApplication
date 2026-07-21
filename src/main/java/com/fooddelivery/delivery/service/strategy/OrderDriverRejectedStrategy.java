package com.fooddelivery.delivery.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.delivery.service.LogisticsDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderDriverRejectedStrategy implements DeliveryEventStrategy {

    private final LogisticsDispatchService logisticsDispatchService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void process(JsonNode root, String eventType) throws Exception {
        UUID orderId = UUID.fromString(root.path("orderId").asText());
        log.info("Delivery Application received ORDER_DRIVER_REJECTED for order {}. Fetching original payload to retry dispatch...", orderId);
        String cachedPayload = redisTemplate.opsForValue().get("order:dispatchPayload:" + orderId);
        if (cachedPayload != null) {
            JsonNode cachedRoot = objectMapper.readTree(cachedPayload);
            double lat = cachedRoot.path("restaurantLat").asDouble(0.0);
            double lng = cachedRoot.path("restaurantLng").asDouble(0.0);
            double deliveryLat = cachedRoot.path("deliveryLat").asDouble(0.0);
            double deliveryLng = cachedRoot.path("deliveryLng").asDouble(0.0);
            String deliveryAddress = cachedRoot.path("deliveryAddress").asText("");
            if (lat != 0.0 && lng != 0.0) {
                logisticsDispatchService.dispatchNearestDriver(lat, lng, deliveryLat, deliveryLng, deliveryAddress, orderId);
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
}
