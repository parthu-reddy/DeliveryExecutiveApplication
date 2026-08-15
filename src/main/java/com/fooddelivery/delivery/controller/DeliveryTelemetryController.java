package com.fooddelivery.delivery.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fooddelivery.delivery.dto.TelemetryEventRequest;
import com.fooddelivery.telemetry.dto.LocationPayload;
import com.fooddelivery.telemetry.service.TelemetryIngestionService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/v1/delivery/telemetry")
@PreAuthorize("hasRole(\'DELIVERY\')")
@lombok.extern.slf4j.Slf4j
public class DeliveryTelemetryController {
    @java.lang.SuppressWarnings("all")

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TelemetryIngestionService telemetryService;
    private static final String DRIVER_LOCATION_KEY = "drivers:geo:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;

    @PostMapping("/batch")
    public ResponseEntity<String> processBatchTelemetry(Principal principal, @Valid @RequestBody List<TelemetryEventRequest> telemetryBatch) {
        log.info("Received telemetry batch of size {}", telemetryBatch.size());
        String authId = principal.getName();
        try {
            // Group valid, non-spoofed payloads for batch ingestion
            List<LocationPayload> validPayloads = new ArrayList<>();
            UUID executiveUuid = UUID.fromString(authId);
            for (TelemetryEventRequest event : telemetryBatch) {
                String driverId = event.getDriverId();
                // IDOR Prevention: Ensure the driver can only send their own telemetry
                if (driverId == null || !driverId.equals(authId)) {
                    log.warn("Unauthorized telemetry push attempt. authId={}, payloadDriverId={}", authId, driverId);
                    continue; // Skip unauthorized events instead of failing the whole batch
                }
                Double lat = event.getLat();
                Double lng = event.getLng();
                if (lat != null && lng != null) {
                    long timestamp = event.getTimestampMs() != null ? event.getTimestampMs() : System.currentTimeMillis();
                    double speed = event.getSpeedKmh() != null ? event.getSpeedKmh() : 0.0;
                    boolean isMock = Boolean.TRUE.equals(event.getIsMockLocation());
                    LocationPayload payload = new LocationPayload(lat, lng, speed, isMock, timestamp);
                    if (isMock) {
                        log.warn("SECURITY BREACH: Mock location detected in batch for driver: {}", driverId);
                        telemetryService.flagAccountForSpoofing(executiveUuid, payload);
                        continue;
                    }
                    validPayloads.add(payload);
                }
            }
            // Batch ingest all valid telemetry payloads in a single transaction
            if (!validPayloads.isEmpty()) {
                telemetryService.batchIngestTelemetry(executiveUuid, validPayloads);
                // Update Redis geospatial index with the last known location
                LocationPayload lastPayload = validPayloads.get(validPayloads.size() - 1);
                redisTemplate.opsForGeo().add(DRIVER_LOCATION_KEY, new Point(lastPayload.longitude(), lastPayload.latitude()), authId);
                redisTemplate.opsForZSet().add("driver_last_ping", authId, System.currentTimeMillis());
            }
            // Publish tracking events for SSE subscribers
            for (TelemetryEventRequest event : telemetryBatch) {
                String orderId = event.getOrderId();
                if (orderId != null && !orderId.isEmpty() && authId.equals(event.getDriverId())) {
                    String channel = "tracking:order:" + orderId;
                    redisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(event));
                }
            }
            return ResponseEntity.ok("Batch processed successfully");
        } catch (Exception e) {
            log.error("Failed to process telemetry batch", e);
            return ResponseEntity.internalServerError().body("Failed to process batch");
        }
    }

    @PostMapping("/sync")
    public ResponseEntity<Void> syncLocation(Principal principal, @RequestBody LocationPayload payload) {
        // IDOR Protection: Derive executive ID from the authenticated principal, NOT from request headers.
        UUID executiveId = UUID.fromString(principal.getName());
        if (payload.isMockLocation()) {
            log.warn("SECURITY BREACH: Geographic Spoofing detected for Executive ID: {}", executiveId);
            telemetryService.flagAccountForSpoofing(executiveId, payload);
            return ResponseEntity.ok().build();
        }
        // Atomic validate + persist to prevent concurrent-ping race condition
        boolean accepted = telemetryService.ingestTelemetry(executiveId, payload);
        if (!accepted) {
            log.warn("SECURITY ALERT: Impossible physics/velocity detected for Executive ID: {}", executiveId);
            return ResponseEntity.ok().build();
        }
        redisTemplate.opsForGeo().add(DRIVER_LOCATION_KEY, new Point(payload.longitude(), payload.latitude()), executiveId.toString());
        redisTemplate.opsForZSet().add("driver_last_ping", executiveId.toString(), System.currentTimeMillis());
        // Publish to live tracking SSE if driver has an active order
        String activeOrderId = redisTemplate.opsForValue().get(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER + executiveId);
        if (activeOrderId != null) {
            try {
                String channel = "tracking:order:" + activeOrderId;
                TelemetryEventRequest trackingEvent = new TelemetryEventRequest();
                trackingEvent.setDriverId(executiveId.toString());
                trackingEvent.setOrderId(activeOrderId);
                trackingEvent.setLat(payload.latitude());
                trackingEvent.setLng(payload.longitude());
                trackingEvent.setSpeedKmh(payload.speedKmh());
                trackingEvent.setTimestampMs(payload.timestampMs());
                trackingEvent.setIsMockLocation(payload.isMockLocation());
                redisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(trackingEvent));
            } catch (Exception e) {
                log.error("Failed to publish live tracking for active order {}", activeOrderId, e);
            }
        }
        return ResponseEntity.ok().build();
    }

    @java.lang.SuppressWarnings("all")
    public DeliveryTelemetryController(final StringRedisTemplate redisTemplate, final ObjectMapper objectMapper, final TelemetryIngestionService telemetryService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.telemetryService = telemetryService;
    }
}
