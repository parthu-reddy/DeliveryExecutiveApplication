package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.UUID;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final ObjectMapper objectMapper;
    private final LogisticsDispatchService logisticsDispatchService;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final IDeliveryExecutiveRepository executiveRepository;

    @KafkaListener(topics = com.fooddelivery.common.constants.KafkaConstants.TOPIC_ORDER_EVENTS, groupId = com.fooddelivery.common.constants.KafkaConstants.GROUP_DELIVERY_SERVICE)
    public void consumeOrderEvent(String message, @org.springframework.messaging.handler.annotation.Header(value = "eventType", required = false) String headerEventType) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String jsonEventType = root.path("eventType").asText(null);
            String eventType = headerEventType != null ? headerEventType : jsonEventType;
            
            if (com.fooddelivery.common.constants.EventType.ORDER_ACCEPTED.equals(eventType)) {
                UUID orderId = UUID.fromString(root.path("orderId").asText());
                double lat = root.path("restaurantLat").asDouble(0.0);
                double lng = root.path("restaurantLng").asDouble(0.0);
                double deliveryLat = root.path("deliveryLat").asDouble(0.0);
                double deliveryLng = root.path("deliveryLng").asDouble(0.0);
                String deliveryAddress = root.path("deliveryAddress").asText("");
                long estimatedCompletionTime = root.path("estimatedCompletionTime").asLong(0L);
                
                long dispatchTime = estimatedCompletionTime > 0 ? estimatedCompletionTime - (15 * 60 * 1000L) : System.currentTimeMillis();
                
                if (lat != 0.0 && lng != 0.0) {
                    Boolean isNewDispatch = redisTemplate.opsForValue().setIfAbsent("order:dispatch:lock:" + orderId, "locked", java.time.Duration.ofHours(24));
                    if (Boolean.TRUE.equals(isNewDispatch)) {
                        if (System.currentTimeMillis() >= dispatchTime) {
                            log.info("Delivery Application received ORDER_ACCEPTED for order {}. Dispatching nearest driver immediately...", orderId);
                            logisticsDispatchService.dispatchNearestDriver(lat, lng, deliveryLat, deliveryLng, deliveryAddress, orderId);
                        } else {
                            log.info("Delivery Application received ORDER_ACCEPTED for order {}. Scheduling dispatch at {}.", orderId, dispatchTime);
                            redisTemplate.opsForValue().set("order:dispatchPayload:" + orderId, message);
                            redisTemplate.opsForZSet().add("delayed_dispatch_queue", orderId.toString(), dispatchTime);
                        }
                    } else {
                        log.info("Duplicate ORDER_ACCEPTED dispatch event ignored for order {}", orderId);
                    }
                } else {
                    log.warn("Missing restaurant location in ORDER_ACCEPTED event for order {}. Cannot dispatch driver.", orderId);
                }
            } else if (com.fooddelivery.common.constants.EventType.ORDER_DRIVER_REJECTED.equals(eventType)) {
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
            } else if (com.fooddelivery.common.constants.EventType.DISPATCH_CANDIDATE_FOUND.equals(eventType)) {
                UUID orderId = UUID.fromString(root.path("orderId").asText());
                String driverId = root.path("driverId").asText(null);
                log.info("Delivery Application received DISPATCH_CANDIDATE_FOUND for order {}. Driver {} will be pinged.", orderId, driverId);
                // Here we would typically push a notification to the driver's app over WebSockets.
                // For now, we simulate this by logging the action.
                log.info("Pinging Driver {} for Order {}...", driverId, orderId);
            } else if (com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED.equals(eventType) || com.fooddelivery.common.constants.EventType.DISPATCH_FAILED.equals(eventType) || com.fooddelivery.common.constants.EventType.ORDER_CANCELLED.equals(eventType) || com.fooddelivery.common.constants.EventType.DELIVERY_FAILED.equals(eventType) || com.fooddelivery.common.constants.EventType.ORDER_CANCELLED_BY_RESTAURANT.equals(eventType) || com.fooddelivery.common.constants.EventType.ORDER_REJECTED.equals(eventType) || com.fooddelivery.common.constants.EventType.ORDER_DELAY_REJECTED.equals(eventType)) {
                UUID orderId = UUID.fromString(root.path("orderId").asText());
                String driverId = root.path("driverId").asText(null);
                log.info("Delivery Application received {} for order {}. Cleaning up pending dispatches.", eventType, orderId);
                
                // Cleanup the Redis dispatch payload on any terminal/successful state
                redisTemplate.delete("order:dispatchPayload:" + orderId);
                redisTemplate.opsForZSet().remove("delayed_dispatch_queue", orderId.toString());

                if (com.fooddelivery.common.constants.EventType.ORDER_CANCELLED.equals(eventType) || com.fooddelivery.common.constants.EventType.DELIVERY_FAILED.equals(eventType) || com.fooddelivery.common.constants.EventType.ORDER_CANCELLED_BY_RESTAURANT.equals(eventType) || com.fooddelivery.common.constants.EventType.ORDER_REJECTED.equals(eventType) || com.fooddelivery.common.constants.EventType.ORDER_DELAY_REJECTED.equals(eventType)) {
                    if (driverId == null || driverId.isEmpty()) {
                        driverId = redisTemplate.opsForValue().get("order:driver:lock:" + orderId);
                    }
                    if (driverId != null && !driverId.isEmpty()) {
                        logisticsDispatchService.releaseDriverLock(driverId);
                        
                        // Reset driver status in DB
                        try {
                            DeliveryExecutive executive = executiveRepository.findById(UUID.fromString(driverId)).orElse(null);
                            if (executive != null && executive.getStatus() == DeliveryExecutiveStatus.ON_DELIVERY) {
                                executive.setStatus(DeliveryExecutiveStatus.ONLINE);
                                executive.setUpdatedAt(java.time.LocalDateTime.now());
                                executiveRepository.save(executive);
                                log.info("Reset driver {} to ONLINE after order {} was cancelled.", driverId, orderId);
                            }
                        } catch (Exception ex) {
                            log.error("Failed to reset driver status in DB", ex);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to process order event in DeliveryExecutiveApplication", e);
            throw new RuntimeException("Failed to process order event in DeliveryExecutiveApplication", e);
        }
    }
}
