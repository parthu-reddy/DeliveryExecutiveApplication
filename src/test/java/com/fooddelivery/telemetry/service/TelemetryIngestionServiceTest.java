package com.fooddelivery.telemetry.service;

import com.fooddelivery.telemetry.dto.LocationPayload;
import com.fooddelivery.telemetry.entity.TelemetryLog;
import com.fooddelivery.telemetry.repository.TelemetryLogRepository;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelemetryIngestionServiceTest {

    @Mock
    private TelemetryLogRepository telemetryLogRepository;

    @Mock
    private IDeliveryExecutiveRepository deliveryExecutiveRepository;

    @InjectMocks
    private TelemetryIngestionService telemetryIngestionService;

    private GeometryFactory geometryFactory;
    private UUID executiveId;

    @BeforeEach
    void setUp() {
        geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
        executiveId = UUID.randomUUID();
    }

    @Test
    void testValidateVelocity_FirstPing_ReturnsTrue() {
        when(telemetryLogRepository.findLatestLogByExecutiveId(executiveId)).thenReturn(Optional.empty());

        LocationPayload payload = new LocationPayload(12.9716, 77.5946, 30.0, false, System.currentTimeMillis());
        boolean isValid = telemetryIngestionService.validateVelocity(executiveId, payload);

        assertTrue(isValid);
    }

    @Test
    void testValidateVelocity_ValidSpeed_ReturnsTrue() {
        long baseTimeMs = System.currentTimeMillis() - 10000; // 10 seconds ago
        TelemetryLog lastLog = new TelemetryLog();
        Point point = geometryFactory.createPoint(new Coordinate(77.5946, 12.9716)); // Bangalore
        lastLog.setLocation(point);
        lastLog.setRecordedAt(OffsetDateTime.ofInstant(Instant.ofEpochMilli(baseTimeMs), ZoneOffset.UTC));

        when(telemetryLogRepository.findLatestLogByExecutiveId(executiveId)).thenReturn(Optional.of(lastLog));

        // Move a tiny distance (e.g., ~100 meters) in 10 seconds => 10 m/s = 36 km/h (Valid)
        LocationPayload payload = new LocationPayload(12.9725, 77.5946, 36.0, false, System.currentTimeMillis());
        boolean isValid = telemetryIngestionService.validateVelocity(executiveId, payload);

        assertTrue(isValid);
    }

    @Test
    void testValidateVelocity_ImpossibleSpeed_ReturnsFalse() {
        long baseTimeMs = System.currentTimeMillis() - 10000; // 10 seconds ago
        TelemetryLog lastLog = new TelemetryLog();
        Point point = geometryFactory.createPoint(new Coordinate(77.5946, 12.9716));
        lastLog.setLocation(point);
        lastLog.setRecordedAt(OffsetDateTime.ofInstant(Instant.ofEpochMilli(baseTimeMs), ZoneOffset.UTC));

        when(telemetryLogRepository.findLatestLogByExecutiveId(executiveId)).thenReturn(Optional.of(lastLog));

        // Move ~10 kilometers in 10 seconds => 1000 m/s = 3600 km/h (Impossible)
        LocationPayload payload = new LocationPayload(13.0616, 77.5946, 3600.0, false, System.currentTimeMillis());
        boolean isValid = telemetryIngestionService.validateVelocity(executiveId, payload);

        assertFalse(isValid);
    }

    @Test
    void testFlagAccountForSpoofing_DeactivatesExecutive() {
        DeliveryExecutive executive = new DeliveryExecutive();
        executive.setId(executiveId);
        executive.setActive(true);

        when(deliveryExecutiveRepository.findById(executiveId)).thenReturn(Optional.of(executive));

        LocationPayload payload = new LocationPayload(12.9716, 77.5946, 0.0, true, System.currentTimeMillis());
        telemetryIngestionService.flagAccountForSpoofing(executiveId, payload);

        assertFalse(executive.isActive());
        verify(deliveryExecutiveRepository, times(1)).save(executive);
    }
}
