package com.fooddelivery.delivery.service.strategy;


import com.fooddelivery.common.constants.EventType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.delivery.service.LogisticsDispatchService;
import java.util.Arrays;
import java.util.List;

@Component
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class OrderStatusUpdatedStrategy implements DeliveryEventStrategy<com.fooddelivery.common.event.OrderScopedEvent> {
private final StringRedisTemplate redisTemplate;
    private final LogisticsDispatchService logisticsDispatchService;
    private final ObjectMapper objectMapper;

    @Override
    public Class<com.fooddelivery.common.event.OrderScopedEvent> eventClass() {
        return com.fooddelivery.common.event.OrderScopedEvent.class;
    }

    @Override
    public void handle(com.fooddelivery.common.event.OrderScopedEvent event, String eventType) throws Exception {
        // The order id is on the interface, so the four instanceof branches that existed only to
        // read it are gone. What remains is genuinely type-specific: three of these event types
        // IMPLY their status, and only ORDER_STATUS_UPDATED carries one on the wire.
        java.util.UUID orderUuid = event.orderUuid();
        String orderId = orderUuid != null ? orderUuid.toString() : null;
        String status = null;
        if (EventType.ORDER_STATUS_UPDATED.name().equals(eventType)) {
            status = event instanceof com.fooddelivery.common.event.OrderStatusUpdatedEvent updated
                    ? updated.getStatus()
                    : null;
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
                Double score = redisTemplate.opsForZSet().score(com.fooddelivery.common.constants.RedisKeyConstants.QUEUE_DELAYED_DISPATCH, orderId);
                if (score != null) {
                    // Try to acquire the processing lock for this order
                    String lockKey = "dispatch_processing_lock:" + orderId;
                    Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", java.time.Duration.ofSeconds(30));
                    if (Boolean.TRUE.equals(acquired)) {
                        try {
                            log.info("Order {} is ready early! Acquired lock, triggering immediate dispatch.", orderId);
                            String payload = redisTemplate.opsForValue().get(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_PAYLOAD + orderId);
                            if (payload != null) {
                                com.fooddelivery.common.event.OrderAcceptedEvent cachedEvent = objectMapper.readValue(payload, com.fooddelivery.common.event.OrderAcceptedEvent.class);
                                double lat = cachedEvent.getRestaurantLat() != null ? cachedEvent.getRestaurantLat() : 0.0;
                                double lng = cachedEvent.getRestaurantLng() != null ? cachedEvent.getRestaurantLng() : 0.0;
                                double deliveryLat = cachedEvent.getDeliveryLat() != null ? cachedEvent.getDeliveryLat() : 0.0;
                                double deliveryLng = cachedEvent.getDeliveryLng() != null ? cachedEvent.getDeliveryLng() : 0.0;
                                String deliveryAddress = cachedEvent.getDeliveryAddress() != null ? cachedEvent.getDeliveryAddress() : "";
                                String dispatchCityId = cachedEvent.getDispatchCityId();
                                double fleetSearchRadiusKm = cachedEvent.getFleetSearchRadiusKm() != null ? cachedEvent.getFleetSearchRadiusKm() : 0.0;
                                if (dispatchCityId == null || dispatchCityId.isBlank() || fleetSearchRadiusKm <= 0) {
                                    throw new IllegalStateException("Cached dispatch payload has no valid city/radius for order " + orderId);
                                }
                                java.util.Set<String> rejectedDrivers = redisTemplate.opsForSet().members(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_REJECTED_DRIVERS + orderId);
                                java.util.List<String> excludedDriverIds = rejectedDrivers != null ? new java.util.ArrayList<>(rejectedDrivers) : null;
                                logisticsDispatchService.dispatchNearestDriver(dispatchCityId, fleetSearchRadiusKm, lat, lng, deliveryLat, deliveryLng, deliveryAddress, java.util.UUID.fromString(orderId), excludedDriverIds);
                            }
                            // Remove from delayed queue ONLY after successful dispatch
                            redisTemplate.opsForZSet().remove(com.fooddelivery.common.constants.RedisKeyConstants.QUEUE_DELAYED_DISPATCH, orderId);
                        } catch (Exception e) {
                            redisTemplate.delete(lockKey);
                            throw e;
                        }
                    }
                }
            }
        } else {
            log.warn("Ignoring {} for order {}: no usable status on the event.", eventType, orderId);
        }
    }

    @Override
    public List<String> getEventTypes() {
        return Arrays.asList(EventType.ORDER_STATUS_UPDATED.name(), EventType.ORDER_READY.name(), EventType.ORDER_PREPARING.name(), EventType.ORDER_ACCEPTED.name());
    }

}
