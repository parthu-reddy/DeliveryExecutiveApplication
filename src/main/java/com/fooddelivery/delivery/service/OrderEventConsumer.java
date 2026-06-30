package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final ObjectMapper objectMapper;
    private final LogisticsDispatchService logisticsDispatchService;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @KafkaListener(topics = "order-events", groupId = "delivery-service-group")
    public void consumeOrderEvent(String message, @org.springframework.messaging.handler.annotation.Header(value = "eventType", required = false) String headerEventType) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String jsonEventType = root.path("eventType").asText(null);
            String eventType = headerEventType != null ? headerEventType : jsonEventType;
            
            if ("ORDER_ACCEPTED".equals(eventType)) {
                UUID orderId = UUID.fromString(root.path("orderId").asText());
                double lat = root.path("restaurantLat").asDouble(0.0);
                double lng = root.path("restaurantLng").asDouble(0.0);
                long estimatedCompletionTime = root.path("estimatedCompletionTime").asLong(0L);
                
                long dispatchTime = estimatedCompletionTime > 0 ? estimatedCompletionTime - (15 * 60 * 1000L) : System.currentTimeMillis();
                
                if (lat != 0.0 && lng != 0.0) {
                    if (System.currentTimeMillis() >= dispatchTime) {
                        log.info("Delivery Application received ORDER_ACCEPTED for order {}. Dispatching nearest driver immediately...", orderId);
                        logisticsDispatchService.dispatchNearestDriver(lat, lng, orderId);
                    } else {
                        log.info("Delivery Application received ORDER_ACCEPTED for order {}. Scheduling dispatch at {}.", orderId, dispatchTime);
                        redisTemplate.opsForValue().set("order:dispatchPayload:" + orderId, message);
                        redisTemplate.opsForZSet().add("delayed_dispatch_queue", orderId.toString(), dispatchTime);
                    }
                } else {
                    log.warn("Missing restaurant location in ORDER_ACCEPTED event for order {}. Cannot dispatch driver.", orderId);
                }
            } else if ("ORDER_DRIVER_REJECTED".equals(eventType)) {
                UUID orderId = UUID.fromString(root.path("orderId").asText());
                log.info("Delivery Application received ORDER_DRIVER_REJECTED for order {}. Fetching original payload to retry dispatch...", orderId);
                String cachedPayload = redisTemplate.opsForValue().get("order:dispatchPayload:" + orderId);
                if (cachedPayload != null) {
                    JsonNode cachedRoot = objectMapper.readTree(cachedPayload);
                    double lat = cachedRoot.path("restaurantLat").asDouble(0.0);
                    double lng = cachedRoot.path("restaurantLng").asDouble(0.0);
                    if (lat != 0.0 && lng != 0.0) {
                        logisticsDispatchService.dispatchNearestDriver(lat, lng, orderId);
                    } else {
                        log.warn("Cached payload for order {} has missing coordinates. Cannot redispatch.", orderId);
                    }
                } else {
                    log.warn("No cached dispatch payload found for order {}. Cannot redispatch driver.", orderId);
                }
            } else if ("DISPATCH_CANDIDATE_FOUND".equals(eventType)) {
                UUID orderId = UUID.fromString(root.path("orderId").asText());
                String driverId = root.path("driverId").asText(null);
                log.info("Delivery Application received DISPATCH_CANDIDATE_FOUND for order {}. Driver {} will be pinged.", orderId, driverId);
                // Here we would typically push a notification to the driver's app over WebSockets.
                // For now, we simulate this by logging the action.
                log.info("Pinging Driver {} for Order {}...", driverId, orderId);
            } else if ("DRIVER_ASSIGNED".equals(eventType) || "DISPATCH_FAILED".equals(eventType) || "ORDER_CANCELLED".equals(eventType) || "DELIVERY_FAILED".equals(eventType) || "ORDER_CANCELLED_BY_RESTAURANT".equals(eventType) || "ORDER_REJECTED".equals(eventType)) {
                UUID orderId = UUID.fromString(root.path("orderId").asText());
                String driverId = root.path("driverId").asText(null);
                log.info("Delivery Application received {} for order {}. Cleaning up pending dispatches.", eventType, orderId);
                
                // Cleanup the Redis dispatch payload on any terminal/successful state
                redisTemplate.delete("order:dispatchPayload:" + orderId);
                redisTemplate.opsForZSet().remove("delayed_dispatch_queue", orderId.toString());

                if (("ORDER_CANCELLED".equals(eventType) || "DELIVERY_FAILED".equals(eventType)) && driverId != null && !driverId.isEmpty()) {
                    logisticsDispatchService.releaseDriverLock(driverId);
                }
            }
        } catch (Exception e) {
            log.error("Failed to process order event in DeliveryExecutiveApplication", e);
        }
    }
}
