package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.KafkaConstants;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fooddelivery.common.client.MapsServiceClient;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class LogisticsDispatchService {
private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MapsServiceClient mapsClient;
    private final IDeliveryExecutiveRepository repository;

    public void dispatchNearestDriver(double restaurantLat, double restaurantLng, double deliveryLat, double deliveryLng, String deliveryAddress, UUID orderId, java.util.List<String> excludedDriverIds) {
        log.info("Requesting driver dispatch for order {} via MapsIntegration service", orderId);
        try {
            Map<String, Object> dispatchRequest = new java.util.HashMap<>();
            dispatchRequest.put("orderId", orderId.toString());
            dispatchRequest.put("restaurantLat", restaurantLat);
            dispatchRequest.put("restaurantLng", restaurantLng);
            dispatchRequest.put("deliveryLat", deliveryLat);
            dispatchRequest.put("deliveryLng", deliveryLng);
            dispatchRequest.put("deliveryAddress", deliveryAddress);
            if (excludedDriverIds != null && !excludedDriverIds.isEmpty()) {
                dispatchRequest.put("excludedDriverIds", excludedDriverIds);
            } else {
                dispatchRequest.put("excludedDriverIds", java.util.Collections.emptyList());
            }
            String payload = objectMapper.writeValueAsString(dispatchRequest);
            log.info("Triggering event: LOGISTICS_DISPATCH_REQUEST for order: {}", orderId);
            // Deliberately synchronous (bypassing outbox) to minimize latency for driver dispatch
            org.springframework.messaging.Message<String> message = org.springframework.messaging.support.MessageBuilder
                .withPayload(payload)
                .setHeader(org.springframework.kafka.support.KafkaHeaders.TOPIC, KafkaConstants.TOPIC_LOGISTICS_DISPATCH)
                .setHeader(org.springframework.kafka.support.KafkaHeaders.KEY, orderId.toString())
                .setHeader("eventId", UUID.randomUUID().toString())
                .build();
            kafkaTemplate.send(message).get(3, java.util.concurrent.TimeUnit.SECONDS);
            log.info("Successfully published dispatch request for order {}", orderId);
        } catch (Exception e) {
            log.error("Failed to serialize or publish dispatch request for order {}", orderId, e);
            throw new RuntimeException("Failed to publish dispatch request", e);
        }
    }

    public void releaseDriverLock(String driverId) {
        log.info("Requesting driver lock release for driver {} via MapsIntegration service", driverId);
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String cityId = repository.findById(UUID.fromString(driverId)).map(DeliveryExecutive::getCityId).orElse(null);
                if (cityId == null) {
                    log.warn("Cannot release driver lock for driver {}: cityId is null", driverId);
                    return;
                }
                com.fooddelivery.common.dto.maps.SetAvailabilityRequest request = new com.fooddelivery.common.dto.maps.SetAvailabilityRequest();
                request.setCityId(cityId);
                request.setDriverId(driverId);
                request.setAvailable(true);
                log.info("Sending request to MapsIntegration /api/fleet/release: {}", request);
                Map<String, Object> response = mapsClient.releaseDriver(request);
                log.info("Successfully requested driver lock release for driver {}. Response: {}", driverId, response);
                return; // Success
            } catch (Exception e) {
                log.error("Failed to release driver lock for driver {} via FeignClient (Attempt {}/{}). Error: {}", driverId, attempt, maxRetries, e.getMessage(), e);
                if (attempt == maxRetries) {
                    throw new RuntimeException("Failed to release driver lock after " + maxRetries + " attempts", e);
                }
                try {
                    Thread.sleep(1000 * attempt); // Exponential backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during lock release retry", ie);
                }
            }
        }
    }

}
