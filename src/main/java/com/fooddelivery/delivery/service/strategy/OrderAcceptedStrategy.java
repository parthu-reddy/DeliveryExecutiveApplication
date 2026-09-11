package com.fooddelivery.delivery.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
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
public class OrderAcceptedStrategy implements DeliveryEventStrategy {
private final LogisticsDispatchService logisticsDispatchService;
    private final StringRedisTemplate redisTemplate;

    @Override
    public void process(JsonNode root, String eventType) throws Exception {
        UUID orderId = UUID.fromString(root.path("orderId").asText());
        double lat = root.path("restaurantLat").asDouble(0.0);
        double lng = root.path("restaurantLng").asDouble(0.0);
        double deliveryLat = root.path("deliveryLat").asDouble(0.0);
        double deliveryLng = root.path("deliveryLng").asDouble(0.0);
        String deliveryAddress = root.path("deliveryAddress").asText("");
        long estimatedCompletionTime = root.path("estimatedCompletionTime").asLong(0L);
        long dispatchTime = estimatedCompletionTime > 0 ? estimatedCompletionTime - (15 * 60 * 1000L) : System.currentTimeMillis();
        if (lat == 0.0 || lng == 0.0) {
            log.warn("Missing restaurant location in ORDER_ACCEPTED event for order {}. Cannot dispatch driver.", orderId);
            return;
        }

        // After commit, not inside the transaction. OrderEventConsumer runs this strategy inside
        // transactionTemplate.execute, in the same transaction as the idempotency claim. Taking the
        // Redis lock there meant a failure at *commit* rolled the claim back and left the lock set:
        // the redelivery then logged "Duplicate ORDER_ACCEPTED dispatch event ignored" and the order
        // was never dispatched, silently. Redis does not participate in the transaction, so the only
        // safe moment to take a lock that outlives it is once the transaction is known to have
        // committed.
        Runnable dispatch = () -> dispatchOnce(root, orderId, lat, lng, deliveryLat, deliveryLng,
                deliveryAddress, dispatchTime);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            dispatch.run();
                        }
                    });
        } else {
            dispatch.run();
        }
    }

    /** The lock, the cached payload and the dispatch itself. Runs at most once per order. */
    private void dispatchOnce(JsonNode root, UUID orderId, double lat, double lng, double deliveryLat,
                              double deliveryLng, String deliveryAddress, long dispatchTime) {
        Boolean isNewDispatch = redisTemplate.opsForValue().setIfAbsent(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_LOCK + orderId, "locked", java.time.Duration.ofHours(24));
        if (!Boolean.TRUE.equals(isNewDispatch)) {
            log.info("Duplicate ORDER_ACCEPTED dispatch event ignored for order {}", orderId);
            return;
        }
        try {
            // ALWAYS store the payload with a TTL so retries can work if drivers reject/timeout
            redisTemplate.opsForValue().set(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_PAYLOAD + orderId, root.toString(), java.time.Duration.ofHours(24));
            log.info("Cached ORDER_ACCEPTED payload in Redis for orderId: {} with pickupOtp: '{}', deliveryOtp: '{}'", orderId, root.path("pickupOtp").asText(""), root.path("deliveryOtp").asText(""));
            if (System.currentTimeMillis() >= dispatchTime) {
                log.info("Delivery Application received ORDER_ACCEPTED for order {}. Dispatching nearest driver immediately...", orderId);
                logisticsDispatchService.dispatchNearestDriver(lat, lng, deliveryLat, deliveryLng, deliveryAddress, orderId, null);
            } else {
                log.info("Delivery Application received ORDER_ACCEPTED for order {}. Scheduling dispatch at {}.", orderId, dispatchTime);
                redisTemplate.opsForZSet().add("delayed_dispatch_queue", orderId.toString(), dispatchTime);
            }
        } catch (RuntimeException e) {
            // The lock must not outlive a dispatch that did not happen, or the order can never be
            // dispatched by anyone.
            redisTemplate.delete(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_LOCK + orderId);
            throw e;
        }
    }

    @Override
    public List<String> getEventTypes() {
        return java.util.Arrays.asList(EventType.ORDER_ACCEPTED.name());
    }

}
