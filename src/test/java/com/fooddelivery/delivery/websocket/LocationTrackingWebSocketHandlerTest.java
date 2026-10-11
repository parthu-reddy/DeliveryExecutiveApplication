package com.fooddelivery.delivery.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.*;

class LocationTrackingWebSocketHandlerTest {

    private LocationTrackingWebSocketHandler handler;
    private ObjectMapper objectMapper;
    private StringRedisTemplate redisTemplate;
    private MeterRegistry meterRegistry;
    private WebSocketSession session;
    private Counter counter;
    private ValueOperations<String, String> valueOps;
    private GeoOperations<String, String> geoOps;
    private com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository repository;
    private com.fooddelivery.delivery.service.location.RiderLocationGuard locationGuard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        objectMapper = new ObjectMapper();
        redisTemplate = mock(StringRedisTemplate.class);
        meterRegistry = mock(MeterRegistry.class);
        repository = mock(com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository.class);
        locationGuard = mock(com.fooddelivery.delivery.service.location.RiderLocationGuard.class);
        // The guard's rules have their own test (RiderLocationGuardTest); here it accepts and stamps server time.
        when(locationGuard.accept(any(java.util.UUID.class), anyDouble(), anyDouble())).thenAnswer(inv ->
                java.util.Optional.of(new com.fooddelivery.delivery.service.location.RiderLocationGuard.Fix(
                        inv.getArgument(0).toString(), "BLR", inv.getArgument(1), inv.getArgument(2), System.currentTimeMillis())));
        handler = new LocationTrackingWebSocketHandler(objectMapper, redisTemplate, meterRegistry, repository,
                new com.fooddelivery.delivery.service.duty.RiderDutyNotifier(redisTemplate, objectMapper), locationGuard);

        session = mock(WebSocketSession.class);
        counter = mock(Counter.class);
        valueOps = mock(ValueOperations.class);
        geoOps = mock(GeoOperations.class);

        when(meterRegistry.counter(anyString())).thenReturn(counter);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForGeo()).thenReturn(geoOps);
    }

    @Test
    void shouldDropMessageWhenIdentityMismatch() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("userId", "4f4a4e37-6ca5-5598-94f1-43ef1628f631");
        when(session.getAttributes()).thenReturn(attributes);

        String payload = "{\"driverId\":\"9b2e7c1a-0000-4000-8000-000000000456\", \"lat\":12.34, \"lng\":56.78}";
        TextMessage message = new TextMessage(payload);

        handler.handleTextMessage(session, message);

        verify(meterRegistry).counter("ws.telemetry.identity_mismatch");
        verify(counter).increment();
        // Since it's reactive, we wait for a moment just in case, but it should not call tryEmitNext
        Thread.sleep(100);
        verify(locationGuard, never()).accept(any(), anyDouble(), anyDouble());
    }

    @Test
    void shouldAcceptMessageWhenIdentityMatches() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("userId", "4f4a4e37-6ca5-5598-94f1-43ef1628f631");
        when(session.getAttributes()).thenReturn(attributes);

        String payload = "{\"driverId\":\"4f4a4e37-6ca5-5598-94f1-43ef1628f631\", \"lat\":12.34, \"lng\":56.78}";
        TextMessage message = new TextMessage(payload);

        handler.handleTextMessage(session, message);

        verify(meterRegistry, never()).counter("ws.telemetry.identity_mismatch");
        // We might not be able to easily test the sink output synchronously due to backpressure/buffer, 
        // but we can verify it doesn't increment the mismatch counter.
    }

    @Test
    void shouldIgnoreOrderIdWhenUnassigned() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("userId", "4f4a4e37-6ca5-5598-94f1-43ef1628f631");
        when(session.getAttributes()).thenReturn(attributes);

        String payload = "{\"driverId\":\"4f4a4e37-6ca5-5598-94f1-43ef1628f631\", \"orderId\":\"order-789\", \"lat\":12.34, \"lng\":56.78}";
        TextMessage message = new TextMessage(payload);

        when(valueOps.get("driver:active_order:4f4a4e37-6ca5-5598-94f1-43ef1628f631")).thenReturn("order-999"); // different order

        handler.handleTextMessage(session, message);

        verify(valueOps).get("driver:active_order:4f4a4e37-6ca5-5598-94f1-43ef1628f631");
        // Sink emits the event, and since orderId is stripped, it should not publish to redis pub/sub 
        // tracking:order:order-789 for this event in processBatch (not tested directly here, but logic holds).
    }

    /**
     * A DUTY_STATUS push published while the socket was down reaches nobody. The snapshot on connect
     * is what corrects an app that still shows "Online Duty" after the sweeper demoted the rider.
     */
    @Test
    void connectSendsTheDutyStatusTheDatabaseHolds() throws Exception {
        java.util.UUID driverId = java.util.UUID.randomUUID();
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("userId", driverId.toString());
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn("s-1");
        when(session.isOpen()).thenReturn(true);
        com.fooddelivery.delivery.entity.DeliveryExecutive rider = new com.fooddelivery.delivery.entity.DeliveryExecutive();
        rider.setId(driverId);
        rider.setStatus(com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.OFFLINE);
        when(repository.findById(driverId)).thenReturn(java.util.Optional.of(rider));

        handler.afterConnectionEstablished(session);

        org.mockito.ArgumentCaptor<TextMessage> sent = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sent.capture());
        com.fasterxml.jackson.databind.JsonNode json = objectMapper.readTree(sent.getValue().getPayload());
        org.assertj.core.api.Assertions.assertThat(json.get("type").asText()).isEqualTo("DUTY_STATUS");
        org.assertj.core.api.Assertions.assertThat(json.get("status").asText()).isEqualTo("OFFLINE");
        org.assertj.core.api.Assertions.assertThat(json.get("reason").asText()).isEqualTo("CONNECTED");
    }

    @Test
    void connectOfAUserWithNoRiderRowSendsNothing() throws Exception {
        java.util.UUID userId = java.util.UUID.randomUUID();
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("userId", userId.toString());
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn("s-2");
        when(repository.findById(userId)).thenReturn(java.util.Optional.empty());

        handler.afterConnectionEstablished(session);

        verify(session, never()).sendMessage(any());
    }

    // ---------- live ETA snapshot (A5b) ----------

    /** The WebSocket is the path the rider app uses; its 1 s flush writes the order's snapshot. */
    @Test
    @SuppressWarnings("unchecked")
    void flushWritesTheSnapshotForTheAssignedOrderAtReceiptTime() throws Exception {
        handler.subscribeToTelemetrySink();
        when(redisTemplate.opsForZSet()).thenReturn(mock(org.springframework.data.redis.core.ZSetOperations.class));
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("userId", "4f4a4e37-6ca5-5598-94f1-43ef1628f631");
        when(session.getAttributes()).thenReturn(attributes);
        when(valueOps.get("driver:active_order:4f4a4e37-6ca5-5598-94f1-43ef1628f631")).thenReturn("order-789");
        long before = System.currentTimeMillis();

        handler.handleTextMessage(session, new TextMessage(
                "{\"driverId\":\"4f4a4e37-6ca5-5598-94f1-43ef1628f631\",\"orderId\":\"order-789\",\"cityId\":\"BLR\",\"lat\":12.97,\"lng\":77.59}"));

        org.mockito.ArgumentCaptor<String> observedAt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redisTemplate, timeout(5000)).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                eq(java.util.List.of("driver:active_order:4f4a4e37-6ca5-5598-94f1-43ef1628f631", "tracking:order-location:order-789")),
                eq("order-789"), anyString(), observedAt.capture(), anyString());
        org.assertj.core.api.Assertions.assertThat(Long.parseLong(observedAt.getValue()))
                .isBetween(before, System.currentTimeMillis());
    }

    @Test
    @SuppressWarnings("unchecked")
    void flushWritesNoSnapshotForAnOrderTheRiderIsNotOn() throws Exception {
        handler.subscribeToTelemetrySink();
        when(redisTemplate.opsForZSet()).thenReturn(mock(org.springframework.data.redis.core.ZSetOperations.class));
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("userId", "4f4a4e37-6ca5-5598-94f1-43ef1628f631");
        when(session.getAttributes()).thenReturn(attributes);
        when(valueOps.get("driver:active_order:4f4a4e37-6ca5-5598-94f1-43ef1628f631")).thenReturn("order-999");

        handler.handleTextMessage(session, new TextMessage(
                "{\"driverId\":\"4f4a4e37-6ca5-5598-94f1-43ef1628f631\",\"orderId\":\"order-789\",\"cityId\":\"BLR\",\"lat\":12.97,\"lng\":77.59}"));

        // The fix was accepted (the guard wrote dispatch's position) ...
        verify(locationGuard).accept(java.util.UUID.fromString("4f4a4e37-6ca5-5598-94f1-43ef1628f631"), 12.97, 77.59);
        Thread.sleep(1500); // one full buffer window: the flush has run
        // ... and the flush wrote no snapshot nor relayed to any order.
        verify(redisTemplate, never()).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(), any(), any(), any(), any());
        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    void nonNumericCoordinatesAreDroppedBeforeTheSink() throws Exception {
        handler.subscribeToTelemetrySink();
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("userId", "4f4a4e37-6ca5-5598-94f1-43ef1628f631");
        when(session.getAttributes()).thenReturn(attributes);

        handler.handleTextMessage(session, new TextMessage(
                "{\"driverId\":\"4f4a4e37-6ca5-5598-94f1-43ef1628f631\",\"orderId\":\"order-789\",\"cityId\":\"BLR\",\"lat\":\"12.97\",\"lng\":77.59}"));

        Thread.sleep(1500); // one full buffer window: the flush would have run by now
        verify(locationGuard, never()).accept(any(), anyDouble(), anyDouble());
        verify(redisTemplate, never()).opsForGeo();
    }
}
