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

import com.fooddelivery.delivery.dto.TelemetryEventRequest;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/v1/delivery/telemetry")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('DELIVERY')")
public class DeliveryTelemetryController {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private static final String DRIVER_LOCATION_KEY = "drivers:geo:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;

    @PostMapping("/batch")
    public ResponseEntity<String> processBatchTelemetry(java.security.Principal principal, @Valid @RequestBody List<TelemetryEventRequest> telemetryBatch) {
        log.info("Received telemetry batch of size {}", telemetryBatch.size());
        
        String authId = principal.getName();
        
        try {
            for (TelemetryEventRequest event : telemetryBatch) {
                String driverId = event.getDriverId();
                
                // IDOR Prevention: Ensure the driver can only send their own telemetry
                if (driverId == null || !driverId.equals(authId)) {
                    log.warn("Unauthorized telemetry push attempt. authId={}, payloadDriverId={}", authId, driverId);
                    continue; // Skip unauthorized events instead of failing the whole batch
                }
                
                Double lat = event.getLat();
                Double lng = event.getLng();
                String orderId = event.getOrderId();
                
                if (lat != null && lng != null) {
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
