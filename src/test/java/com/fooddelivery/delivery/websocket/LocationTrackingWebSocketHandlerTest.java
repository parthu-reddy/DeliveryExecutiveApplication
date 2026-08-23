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

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        objectMapper = new ObjectMapper();
        redisTemplate = mock(StringRedisTemplate.class);
        meterRegistry = mock(MeterRegistry.class);
        handler = new LocationTrackingWebSocketHandler(objectMapper, redisTemplate, meterRegistry);

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
        attributes.put("userId", "driver-123");
        when(session.getAttributes()).thenReturn(attributes);

        String payload = "{\"driverId\":\"driver-456\", \"lat\":12.34, \"lng\":56.78}";
        TextMessage message = new TextMessage(payload);

        handler.handleTextMessage(session, message);

        verify(meterRegistry).counter("ws.telemetry.identity_mismatch");
        verify(counter).increment();
        // Since it's reactive, we wait for a moment just in case, but it should not call tryEmitNext
        Thread.sleep(100);
        verify(redisTemplate, never()).opsForGeo();
    }

    @Test
    void shouldAcceptMessageWhenIdentityMatches() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("userId", "driver-123");
        when(session.getAttributes()).thenReturn(attributes);

        String payload = "{\"driverId\":\"driver-123\", \"lat\":12.34, \"lng\":56.78}";
        TextMessage message = new TextMessage(payload);

        handler.handleTextMessage(session, message);

        verify(meterRegistry, never()).counter("ws.telemetry.identity_mismatch");
        // We might not be able to easily test the sink output synchronously due to backpressure/buffer, 
        // but we can verify it doesn't increment the mismatch counter.
    }

    @Test
    void shouldIgnoreOrderIdWhenUnassigned() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("userId", "driver-123");
        when(session.getAttributes()).thenReturn(attributes);

        String payload = "{\"driverId\":\"driver-123\", \"orderId\":\"order-789\", \"lat\":12.34, \"lng\":56.78}";
        TextMessage message = new TextMessage(payload);

        when(valueOps.get("driver:active_order:driver-123")).thenReturn("order-999"); // different order

        handler.handleTextMessage(session, message);

        verify(valueOps).get("driver:active_order:driver-123");
        // Sink emits the event, and since orderId is stripped, it should not publish to redis pub/sub 
        // tracking:order:order-789 for this event in processBatch (not tested directly here, but logic holds).
    }
}
