package com.fooddelivery.telemetry.service;

import com.fooddelivery.telemetry.dto.LocationPayload;
import com.fooddelivery.telemetry.entity.TelemetryLog;
import com.fooddelivery.telemetry.repository.TelemetryLogRepository;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.DeliveryExecutiveProfileService;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.concurrent.TimeUnit;

@Service
@lombok.extern.slf4j.Slf4j
public class TelemetryIngestionService {
    @java.lang.SuppressWarnings("all")

    private final TelemetryLogRepository telemetryLogRepository;
    private final IDeliveryExecutiveRepository deliveryExecutiveRepository;
    private final DeliveryExecutiveProfileService profileService;
    private final StringRedisTemplate redisTemplate;
    private static final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    private static final double MAX_LOGICAL_VELOCITY_KMH = 120.0; // Impossible speed for city logistics
    private static final int MAX_STRIKES = 3;
    private static final long STRIKE_TTL_HOURS = 1;

    /**
     * Flags an executive's account for GPS spoofing or velocity anomalies.
     * Uses a 3-strike system in Redis with a 1-hour TTL.
     */
    public void flagAccountForSpoofing(UUID executiveId, LocationPayload payload) {
        log.warn("Anomaly detected for Executive ID: {}", executiveId);
        String strikeKey = "driver:spoof_strikes:" + executiveId;
        Long strikes = redisTemplate.opsForValue().increment(strikeKey);
        if (strikes != null && strikes == 1) {
            redisTemplate.expire(strikeKey, STRIKE_TTL_HOURS, TimeUnit.HOURS);
        }
        if (strikes != null && strikes > MAX_STRIKES) {
            log.warn("Account exceeded maximum spoofing strikes. Deactivating Executive ID: {}", executiveId);
            DeliveryExecutive executive = deliveryExecutiveRepository.findById(executiveId).orElse(null);
            if (executive != null) {
                profileService.deactivateDriver(executiveId);
            } else {
                log.error("SECURITY: Spoofing alert for non-existent executive ID: {}. " + "Possible forged telemetry payload. Lat: {}, Lng: {}", executiveId, payload.latitude(), payload.longitude());
            }
        }
    }

    /**
     * Validates velocity between sequential location pings.
     * This method is NOT transactional on its own — it should be called
     * from within {@link #ingestTelemetry(UUID, LocationPayload)} for atomicity.
     */
    public boolean validateVelocity(UUID executiveId, LocationPayload payload) {
        Optional<TelemetryLog> lastLogOpt = telemetryLogRepository.findLatestLogByExecutiveId(executiveId);
        if (lastLogOpt.isEmpty()) {
            return true; // First ping, no velocity to check against
        }
        TelemetryLog lastLog = lastLogOpt.get();
        OffsetDateTime currentTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(payload.timestampMs()), ZoneOffset.UTC);
        long secondsElapsed = ChronoUnit.SECONDS.between(lastLog.getRecordedAt(), currentTime);
        if (secondsElapsed <= 0) {
            return false; // Time anomaly
        }
        double distanceMeters = calculateHaversineDistance(lastLog.getLocation().getY(), lastLog.getLocation().getX(), payload.latitude(), payload.longitude());
        double speedMs = distanceMeters / secondsElapsed;
        double speedKmh = speedMs * 3.6;
        if (speedKmh > MAX_LOGICAL_VELOCITY_KMH) {
            log.warn("Velocity validation failed for executive {}. Calculated speed: {} km/h over {} seconds", executiveId, speedKmh, secondsElapsed);
            flagAccountForSpoofing(executiveId, payload);
            return false;
        }
        return true;
    }

    /**
     * Atomic telemetry ingestion: validates velocity and persists in a single transaction.
     * This prevents concurrent pings from the same driver both passing validation
     * against the same last log entry.
     */
    @Transactional
    public boolean ingestTelemetry(UUID executiveId, LocationPayload payload) {
        if (!validateVelocity(executiveId, payload)) {
            return false;
        }
        updateDriverLocation(executiveId, payload);
        return true;
    }

    @Transactional
    public void updateDriverLocation(UUID executiveId, LocationPayload payload) {
        DeliveryExecutive executive = deliveryExecutiveRepository.findById(executiveId).orElseThrow(() -> new IllegalArgumentException("Executive not found: " + executiveId));
        if (!executive.isActive()) {
            log.warn("Telemetry rejected: Executive {} is not active (pending onboarding/banned)", executiveId);
            return;
        }
        TelemetryLog logEntry = new TelemetryLog();
        logEntry.setExecutive(executive);
        Point point = geometryFactory.createPoint(new Coordinate(payload.longitude(), payload.latitude()));
        logEntry.setLocation(point);
        logEntry.setSpeedKmh(BigDecimal.valueOf(payload.speedKmh()));
        logEntry.setMockLocation(payload.isMockLocation());
        logEntry.setRecordedAt(OffsetDateTime.ofInstant(Instant.ofEpochMilli(payload.timestampMs()), ZoneOffset.UTC));
        telemetryLogRepository.save(logEntry);
    }

    /**
     * Batch ingests multiple telemetry payloads for the same executive in a single transaction.
     * Validates velocity sequentially across the ordered batch without per-event DB lookups
     * after the first validation.
     */
    @Transactional
    public void batchIngestTelemetry(UUID executiveId, List<LocationPayload> payloads) {
        if (payloads == null || payloads.isEmpty()) return;
        DeliveryExecutive executive = deliveryExecutiveRepository.findById(executiveId).orElseThrow(() -> new IllegalArgumentException("Executive not found: " + executiveId));
        if (!executive.isActive()) {
            log.warn("Batch telemetry rejected: Executive {} is not active", executiveId);
            return;
        }
        // Get the latest log for first velocity check
        Optional<TelemetryLog> lastLogOpt = telemetryLogRepository.findLatestLogByExecutiveId(executiveId);
        TelemetryLog previousLog = lastLogOpt.orElse(null);
        for (LocationPayload payload : payloads) {
            // Validate velocity against previous log (either from DB or from previous batch entry)
            if (previousLog != null) {
                OffsetDateTime currentTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(payload.timestampMs()), ZoneOffset.UTC);
                long secondsElapsed = ChronoUnit.SECONDS.between(previousLog.getRecordedAt(), currentTime);
                if (secondsElapsed > 0) {
                    double distanceMeters = calculateHaversineDistance(previousLog.getLocation().getY(), previousLog.getLocation().getX(), payload.latitude(), payload.longitude());
                    double speedKmh = (distanceMeters / secondsElapsed) * 3.6;
                    if (speedKmh > MAX_LOGICAL_VELOCITY_KMH) {
                        log.warn("Batch velocity validation failed for executive {}. Speed: {} km/h", executiveId, speedKmh);
                        flagAccountForSpoofing(executiveId, payload);
                        continue; // Skip this ping, continue with rest of batch
                    }
                }
            }
            // Create and persist telemetry log entry
            TelemetryLog logEntry = new TelemetryLog();
            logEntry.setExecutive(executive);
            Point point = geometryFactory.createPoint(new Coordinate(payload.longitude(), payload.latitude()));
            logEntry.setLocation(point);
            logEntry.setSpeedKmh(BigDecimal.valueOf(payload.speedKmh()));
            logEntry.setMockLocation(payload.isMockLocation());
            logEntry.setRecordedAt(OffsetDateTime.ofInstant(Instant.ofEpochMilli(payload.timestampMs()), ZoneOffset.UTC));
            previousLog = telemetryLogRepository.save(logEntry);
        }
    }

    private double calculateHaversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // Radius of the earth in km
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c * 1000; // convert to meters
    }

    @java.lang.SuppressWarnings("all")
    public TelemetryIngestionService(final TelemetryLogRepository telemetryLogRepository, final IDeliveryExecutiveRepository deliveryExecutiveRepository, final DeliveryExecutiveProfileService profileService, final StringRedisTemplate redisTemplate) {
        this.telemetryLogRepository = telemetryLogRepository;
        this.deliveryExecutiveRepository = deliveryExecutiveRepository;
        this.profileService = profileService;
        this.redisTemplate = redisTemplate;
    }
}
