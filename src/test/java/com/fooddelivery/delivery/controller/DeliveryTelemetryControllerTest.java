package com.fooddelivery.delivery.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.delivery.dto.TelemetryEventRequest;
import com.fooddelivery.telemetry.dto.LocationPayload;
import com.fooddelivery.telemetry.service.TelemetryIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseEntity;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryTelemetryControllerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private TelemetryIngestionService telemetryService;

    @Mock
    private GeoOperations<String, String> geoOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private Principal principal;

    @Mock
    private com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository repository;

    @InjectMocks
    private DeliveryTelemetryController controller;

    private UUID driverUuid;
    private String driverId;

    @BeforeEach
    void setUp() {
        driverUuid = UUID.randomUUID();
        driverId = driverUuid.toString();
    }

    // ---------- /batch tests ----------

    @Test
    void processBatchTelemetry_ValidEvent_UpdatesLocationAndRedis() throws Exception {
        when(principal.getName()).thenReturn(driverId);
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        com.fooddelivery.delivery.entity.DeliveryExecutive exec = new com.fooddelivery.delivery.entity.DeliveryExecutive();
        exec.setId(driverUuid);
        exec.setCityId("BLR");
        when(repository.findById(driverUuid)).thenReturn(java.util.Optional.of(exec));
        TelemetryEventRequest event = new TelemetryEventRequest();
        event.setDriverId(driverId);
        event.setLat(12.9716);
        event.setLng(77.5946);
        event.setSpeedKmh(30.0);
        event.setIsMockLocation(false);

        ResponseEntity<String> response = controller.processBatchTelemetry(principal, List.of(event));

        assertEquals(200, response.getStatusCode().value());
        // Should call batchIngestTelemetry instead of individual calls
        verify(telemetryService, times(1)).batchIngestTelemetry(eq(driverUuid), anyList());
        verify(geoOperations, times(1)).add(anyString(), any(Point.class), eq(driverId));
        verify(zSetOperations, times(1)).add(anyString(), eq(driverId), anyDouble());
    }

    @Test
    void processBatchTelemetry_IdorAttempt_SkipsEvent() throws Exception {
        when(principal.getName()).thenReturn(UUID.randomUUID().toString()); // Different driver UUID

        TelemetryEventRequest event = new TelemetryEventRequest();
        event.setDriverId(driverId);
        event.setLat(12.9716);
        event.setLng(77.5946);

        ResponseEntity<String> response = controller.processBatchTelemetry(principal, List.of(event));

        assertEquals(200, response.getStatusCode().value());
        verify(telemetryService, never()).batchIngestTelemetry(any(), anyList());
    }

    @Test
    void processBatchTelemetry_MockLocation_FlagsAccountAndSkipsUpdate() throws Exception {
        when(principal.getName()).thenReturn(driverId);

        TelemetryEventRequest event = new TelemetryEventRequest();
        event.setDriverId(driverId);
        event.setLat(12.9716);
        event.setLng(77.5946);
        event.setIsMockLocation(true);

        ResponseEntity<String> response = controller.processBatchTelemetry(principal, List.of(event));

        assertEquals(200, response.getStatusCode().value());
        verify(telemetryService, times(1)).flagAccountForSpoofing(eq(driverUuid), any(LocationPayload.class));
        verify(telemetryService, never()).batchIngestTelemetry(any(), anyList());
    }

    // ---------- /sync tests ----------

    @Test
    void syncLocation_ValidPayload_UpdatesLocationAndRedis() {
        when(principal.getName()).thenReturn(driverId);
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        com.fooddelivery.delivery.entity.DeliveryExecutive exec = new com.fooddelivery.delivery.entity.DeliveryExecutive();
        exec.setId(driverUuid);
        exec.setCityId("BLR");
        when(repository.findById(driverUuid)).thenReturn(java.util.Optional.of(exec));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(telemetryService.ingestTelemetry(eq(driverUuid), any())).thenReturn(true);

        LocationPayload payload = new LocationPayload(12.9716, 77.5946, 30.0, false, System.currentTimeMillis());
        ResponseEntity<Void> response = controller.syncLocation(principal, payload);

        assertEquals(200, response.getStatusCode().value());
        verify(telemetryService, times(1)).ingestTelemetry(eq(driverUuid), eq(payload));
        verify(geoOperations, times(1)).add(anyString(), any(Point.class), eq(driverId));
    }

    @Test
    void syncLocation_MockLocation_FlagsAccount() {
        when(principal.getName()).thenReturn(driverId);

        LocationPayload payload = new LocationPayload(12.9716, 77.5946, 0.0, true, System.currentTimeMillis());
        ResponseEntity<Void> response = controller.syncLocation(principal, payload);

        assertEquals(200, response.getStatusCode().value());
        verify(telemetryService, times(1)).flagAccountForSpoofing(eq(driverUuid), eq(payload));
        verify(telemetryService, never()).ingestTelemetry(any(), any());
    }

    @Test
    void syncLocation_VelocityInvalid_SkipsRedisUpdate() {
        when(principal.getName()).thenReturn(driverId);
        when(telemetryService.ingestTelemetry(eq(driverUuid), any())).thenReturn(false);

        LocationPayload payload = new LocationPayload(12.9716, 77.5946, 3600.0, false, System.currentTimeMillis());
        ResponseEntity<Void> response = controller.syncLocation(principal, payload);

        assertEquals(200, response.getStatusCode().value());
        verify(telemetryService, times(1)).ingestTelemetry(eq(driverUuid), eq(payload));
        verifyNoInteractions(geoOperations);
    }
}
