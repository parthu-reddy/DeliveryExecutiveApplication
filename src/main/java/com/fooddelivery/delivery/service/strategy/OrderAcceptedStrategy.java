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
public class OrderAcceptedStrategy implements DeliveryEventStrategy<com.fooddelivery.common.event.OrderAcceptedEvent> {
private final LogisticsDispatchService logisticsDispatchService;
    private final StringRedisTemplate redisTemplate;

    @Override
    public Class<com.fooddelivery.common.event.OrderAcceptedEvent> eventClass() {
        return com.fooddelivery.common.event.OrderAcceptedEvent.class;
    }

    @Override
    public void handle(com.fooddelivery.common.event.OrderAcceptedEvent event, String eventType) throws Exception {
        UUID orderId = event.orderUuid();
        if (orderId == null) {
            log.warn("Missing orderId in ORDER_ACCEPTED event");
            return;
        }
        double lat = event.getRestaurantLat() != null ? event.getRestaurantLat() : 0.0;
        double lng = event.getRestaurantLng() != null ? event.getRestaurantLng() : 0.0;
        double deliveryLat = event.getDeliveryLat() != null ? event.getDeliveryLat() : 0.0;
        double deliveryLng = event.getDeliveryLng() != null ? event.getDeliveryLng() : 0.0;
        String deliveryAddress = event.getDeliveryAddress() != null ? event.getDeliveryAddress() : "";
        long estimatedCompletionTime = event.getEstimatedCompletionTime() != null ? event.getEstimatedCompletionTime() : 0L;
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
        Runnable dispatch = () -> dispatchOnce(event, orderId, lat, lng, deliveryLat, deliveryLng,
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
    private void dispatchOnce(com.fooddelivery.common.event.OrderAcceptedEvent event, UUID orderId, double lat, double lng, double deliveryLat,
                              double deliveryLng, String deliveryAddress, long dispatchTime) {
        Boolean isNewDispatch = redisTemplate.opsForValue().setIfAbsent(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_LOCK + orderId, "locked", java.time.Duration.ofHours(24));
        if (!Boolean.TRUE.equals(isNewDispatch)) {
            log.info("Duplicate ORDER_ACCEPTED dispatch event ignored for order {}", orderId);
            return;
        }
        try {
            // ALWAYS store the payload with a TTL so retries can work if drivers reject/timeout
            try { 
                redisTemplate.opsForValue().set(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_PAYLOAD + orderId, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(event), java.time.Duration.ofHours(24)); 
            } catch(Exception ex) {
                log.warn("Failed to cache ORDER_ACCEPTED payload for orderId: {}", orderId, ex);
            }
            log.info("Cached ORDER_ACCEPTED payload in Redis for orderId: {}", orderId);
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
