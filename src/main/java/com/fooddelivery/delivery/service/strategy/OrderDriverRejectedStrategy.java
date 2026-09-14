package com.fooddelivery.delivery.service.strategy;

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
@lombok.RequiredArgsConstructor
public class OrderDriverRejectedStrategy implements DeliveryEventStrategy<com.fooddelivery.common.event.OrderDriverRejectedEvent> {
private final LogisticsDispatchService logisticsDispatchService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Class<com.fooddelivery.common.event.OrderDriverRejectedEvent> eventClass() {
        return com.fooddelivery.common.event.OrderDriverRejectedEvent.class;
    }

    @Override
    public void handle(com.fooddelivery.common.event.OrderDriverRejectedEvent event, String eventType) throws Exception {
        UUID orderId = event.orderUuid();
        log.info("Delivery Application received ORDER_DRIVER_REJECTED for order {}. Fetching original payload to retry dispatch...", orderId);
        // Guard: if the order was cancelled/terminal, the dispatch lock is set to "CANCELLED" — skip redispatch
        String dispatchLock = redisTemplate.opsForValue().get(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_LOCK + orderId);
        if ("CANCELLED".equals(dispatchLock)) {
            log.info("Order {} dispatch lock is CANCELLED. Skipping redispatch.", orderId);
            return;
        }
        String cachedPayload = redisTemplate.opsForValue().get(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_PAYLOAD + orderId);
        if (cachedPayload != null) {
            com.fooddelivery.common.event.OrderAcceptedEvent cachedEvent = objectMapper.readValue(cachedPayload, com.fooddelivery.common.event.OrderAcceptedEvent.class);
            double lat = cachedEvent.getRestaurantLat() != null ? cachedEvent.getRestaurantLat() : 0.0;
            double lng = cachedEvent.getRestaurantLng() != null ? cachedEvent.getRestaurantLng() : 0.0;
            double deliveryLat = cachedEvent.getDeliveryLat() != null ? cachedEvent.getDeliveryLat() : 0.0;
            double deliveryLng = cachedEvent.getDeliveryLng() != null ? cachedEvent.getDeliveryLng() : 0.0;
            String deliveryAddress = cachedEvent.getDeliveryAddress() != null ? cachedEvent.getDeliveryAddress() : "";
            if (lat != 0.0 && lng != 0.0) {
                // NOTE: Do NOT increment dispatch_failed_cycles here.
                // The cycle counter is managed exclusively by DelayedDispatchPoller
                // (one increment per re-dispatch attempt). Incrementing here or in
                // TerminalStateStrategy caused double-counting when rejection led to
                // a subsequent DISPATCH_FAILED in the same logical cycle.
                long delayMs = 15000; // 15-second cooldown to allow releaseDriverLock() Feign call to complete
                long dispatchAt = System.currentTimeMillis() + delayMs;
                log.info("Queueing order {} for delayed dispatch retry via poller. Will dispatch at {} ({}ms delay).", orderId, dispatchAt, delayMs);
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

}
