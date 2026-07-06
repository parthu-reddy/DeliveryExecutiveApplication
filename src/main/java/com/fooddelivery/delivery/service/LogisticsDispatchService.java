package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.KafkaConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogisticsDispatchService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final org.springframework.web.client.RestTemplate restTemplate;

    public void dispatchNearestDriver(double restaurantLat, double restaurantLng, double deliveryLat, double deliveryLng, String deliveryAddress, UUID orderId) {
        log.info("Requesting driver dispatch for order {} via MapsIntegration service", orderId);
        
        try {
            Map<String, Object> dispatchRequest = Map.of(
                "orderId", orderId.toString(),
                "restaurantLat", restaurantLat,
                "restaurantLng", restaurantLng,
                "deliveryLat", deliveryLat,
                "deliveryLng", deliveryLng,
                "deliveryAddress", deliveryAddress
            );
            
            String payload = objectMapper.writeValueAsString(dispatchRequest);
            
            kafkaTemplate.send(KafkaConstants.TOPIC_LOGISTICS_DISPATCH, orderId.toString(), payload)
                .get(3, java.util.concurrent.TimeUnit.SECONDS);
            log.info("Successfully published dispatch request for order {}", orderId);
        } catch (Exception e) {
            log.error("Failed to serialize or publish dispatch request for order {}", orderId, e);
            throw new RuntimeException("Failed to publish dispatch request", e);
        }
    }

    private static final String mapsServiceBaseUrl = "http://mapsintegration";

    public void releaseDriverLock(String driverId) {
        log.info("Requesting driver lock release for driver {} via MapsIntegration service", driverId);
        try {
            String url = mapsServiceBaseUrl + "/api/fleet/availability";
            Map<String, Object> request = Map.of(
                "cityId", com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID,
                "driverId", driverId,
                "available", true
            );
            restTemplate.postForEntity(url, request, String.class);
            log.info("Successfully requested driver lock release for driver {}", driverId);
        } catch (Exception e) {
            log.error("Failed to release driver lock for driver {}", driverId, e);
            throw new RuntimeException("Failed to release driver lock", e);
        }
    }
}
