package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.KafkaConstants;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;
import com.fooddelivery.delivery.client.MapsClient;

@Service
public class LogisticsDispatchService {
    @java.lang.SuppressWarnings("all")
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LogisticsDispatchService.class);
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MapsClient mapsClient;

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
            }
            String payload = objectMapper.writeValueAsString(dispatchRequest);
            log.info("Triggering event: LOGISTICS_DISPATCH_REQUEST for order: {}", orderId);
            kafkaTemplate.send(KafkaConstants.TOPIC_LOGISTICS_DISPATCH, orderId.toString(), payload).get(3, java.util.concurrent.TimeUnit.SECONDS);
            log.info("Successfully published dispatch request for order {}", orderId);
        } catch (Exception e) {
            log.error("Failed to serialize or publish dispatch request for order {}", orderId, e);
            throw new RuntimeException("Failed to publish dispatch request", e);
        }
    }

    public void releaseDriverLock(String driverId) {
        log.info("Requesting driver lock release for driver {} via MapsIntegration service", driverId);
        try {
            Map<String, Object> request = Map.of("cityId", com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID, "driverId", driverId, "available", true);
            log.info("Sending request to MapsIntegration /api/fleet/release: {}", request);
            org.springframework.http.ResponseEntity<String> response = mapsClient.releaseDriver(request);
            log.info("Successfully requested driver lock release for driver {}. Response: {}", driverId, response.getStatusCode());
        } catch (Exception e) {
            log.error("Failed to release driver lock for driver {} via FeignClient. Error: {}", driverId, e.getMessage(), e);
            throw new RuntimeException("Failed to release driver lock", e);
        }
    }

    @java.lang.SuppressWarnings("all")
    public LogisticsDispatchService(final KafkaTemplate<String, String> kafkaTemplate, final ObjectMapper objectMapper, final MapsClient mapsClient) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.mapsClient = mapsClient;
    }
}
