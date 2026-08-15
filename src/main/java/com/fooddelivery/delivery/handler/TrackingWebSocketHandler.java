package com.fooddelivery.delivery.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@lombok.extern.slf4j.Slf4j
public class TrackingWebSocketHandler extends TextWebSocketHandler {
    @java.lang.SuppressWarnings("all")

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private static final String DRIVER_LOCATION_KEY = "drivers:geo:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;
    // Use Sinks.Many to create a reactive stream for telemetry data with backpressure buffering
    private final Sinks.Many<Map<String, Object>> telemetrySink = Sinks.many().multicast().onBackpressureBuffer(10000, false);

    @PostConstruct
    public void init() {
        // Subscribe to the sink, batch events every 1 second or 500 items, and process
        telemetrySink.asFlux().bufferTimeout(500, Duration.ofSeconds(1)).publishOn(Schedulers.boundedElastic()).subscribe(this::processBatch, error -> log.error("Error processing telemetry stream", error));
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String userId = (String) session.getAttributes().get("userId");
        if (userId == null) {
            log.warn("Unauthorized WebSocket connection attempt (missing userId in session): {}", session.getId());
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        log.info("WebSocket connection established: {}", session.getId());
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            Map<String, Object> event = objectMapper.readValue(message.getPayload(), Map.class);
            // Emit to sink
            Sinks.EmitResult result = telemetrySink.tryEmitNext(event);
            if (result.isFailure()) {
                log.warn("Failed to emit telemetry event due to backpressure/buffer full");
            }
        } catch (Exception e) {
            log.error("Failed to parse telemetry message", e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("WebSocket connection closed: {}", session.getId());
    }

    private void processBatch(List<Map<String, Object>> batch) {
        if (batch.isEmpty()) return;
        log.info("Processing reactive telemetry batch of size {}", batch.size());
        try {
            for (Map<String, Object> event : batch) {
                String driverId = (String) event.get("driverId");
                Number latNum = (Number) event.get("lat");
                Number lngNum = (Number) event.get("lng");
                String orderId = (String) event.get("orderId");
                if (driverId != null && latNum != null && lngNum != null) {
                    double lat = latNum.doubleValue();
                    double lng = lngNum.doubleValue();
                    // Update geospatial index
                    redisTemplate.opsForGeo().add(DRIVER_LOCATION_KEY, new Point(lng, lat), driverId);
                    // Publish to pub/sub for SSE tracking
                    if (orderId != null && !orderId.isEmpty()) {
                        String channel = "tracking:order:" + orderId;
                        redisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(event));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to process reactive telemetry batch", e);
        }
    }

    @java.lang.SuppressWarnings("all")
    public TrackingWebSocketHandler(final StringRedisTemplate redisTemplate, final ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }
}
