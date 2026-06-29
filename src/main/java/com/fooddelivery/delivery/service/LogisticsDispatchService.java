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

    public void dispatchNearestDriver(double restaurantLat, double restaurantLng, UUID orderId) {
        log.info("Requesting driver dispatch for order {} via MapsIntegration service", orderId);
        
        try {
            Map<String, Object> dispatchRequest = Map.of(
                "orderId", orderId.toString(),
                "restaurantLat", restaurantLat,
                "restaurantLng", restaurantLng
            );
            
            String payload = objectMapper.writeValueAsString(dispatchRequest);
            
            kafkaTemplate.send(KafkaConstants.TOPIC_LOGISTICS_DISPATCH, orderId.toString(), payload)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("Successfully published dispatch request for order {}", orderId);
                    } else {
                        log.error("Failed to publish dispatch request for order {}", orderId, ex);
                    }
                });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize dispatch request for order {}", orderId, e);
        }
    }

    public void releaseDriverLock(String driverId) {
        log.info("Requesting driver lock release for driver {} via MapsIntegration service", driverId);
        try {
            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            String url = "http://localhost:8083/api/fleet/availability";
            Map<String, Object> request = Map.of(
                "cityId", "BLR", // default cityId
                "driverId", driverId,
                "available", true
            );
            restTemplate.postForEntity(url, request, String.class);
            log.info("Successfully requested driver lock release for driver {}", driverId);
        } catch (Exception e) {
            log.error("Failed to release driver lock for driver {}", driverId, e);
        }
    }
}
