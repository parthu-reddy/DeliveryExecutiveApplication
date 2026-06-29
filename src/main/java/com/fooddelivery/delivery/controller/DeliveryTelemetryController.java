package com.fooddelivery.delivery.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/delivery/telemetry")
@RequiredArgsConstructor
@Slf4j
public class DeliveryTelemetryController {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private static final String DRIVER_LOCATION_KEY = "driver_locations";

    @PostMapping("/batch")
    public ResponseEntity<String> processBatchTelemetry(@RequestBody List<Map<String, Object>> telemetryBatch) {
        log.info("Received telemetry batch of size {}", telemetryBatch.size());
        
        try {
            for (Map<String, Object> event : telemetryBatch) {
                String driverId = (String) event.get("driverId");
                Double lat = (Double) event.get("lat");
                Double lng = (Double) event.get("lng");
                String orderId = (String) event.get("orderId");
                
                if (driverId != null && lat != null && lng != null) {
                    // Update geospatial index
                    redisTemplate.opsForGeo().add(DRIVER_LOCATION_KEY, new Point(lng, lat), driverId);
                    
                    // Publish to pub/sub for SSE tracking
                    if (orderId != null && !orderId.isEmpty()) {
                        String channel = "tracking:order:" + orderId;
                        redisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(event));
                    }
                }
            }
            return ResponseEntity.ok("Batch processed successfully");
        } catch (Exception e) {
            log.error("Failed to process telemetry batch", e);
            return ResponseEntity.internalServerError().body("Failed to process batch");
        }
    }
}
