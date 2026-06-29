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
                
                log.info("Delivery Application received ORDER_ACCEPTED for order {}. Dispatching nearest driver...", orderId);
                
                if (lat != 0.0 && lng != 0.0) {
                    logisticsDispatchService.dispatchNearestDriver(lat, lng, orderId);
                } else {
                    log.warn("Missing restaurant location in ORDER_ACCEPTED event for order {}. Cannot dispatch driver.", orderId);
                }
            } else if ("ORDER_CANCELLED".equals(eventType) || "DELIVERY_FAILED".equals(eventType)) {
                UUID orderId = UUID.fromString(root.path("orderId").asText());
                String driverId = root.path("driverId").asText(null);
                log.info("Delivery Application received {} for order {}.", eventType, orderId);
                if (driverId != null && !driverId.isEmpty()) {
                    logisticsDispatchService.releaseDriverLock(driverId);
                } else {
                    log.warn("No driverId provided in {} event for order {}. Cannot release lock.", eventType, orderId);
                }
            }
        } catch (Exception e) {
            log.error("Failed to process order event in DeliveryExecutiveApplication", e);
        }
    }
}
