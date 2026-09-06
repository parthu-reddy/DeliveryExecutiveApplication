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
@lombok.RequiredArgsConstructor
public class TrackingWebSocketHandler extends TextWebSocketHandler {
private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    // Use Sinks.Many to create a reactive stream for telemetry data with backpressure buffering
    private final Sinks.Many<com.fooddelivery.delivery.dto.FleetTrackingUpdateDto> telemetrySink = Sinks.many().multicast().onBackpressureBuffer(10000, false);

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
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            String sessionUser = (String) session.getAttributes().get("userId");
            com.fooddelivery.delivery.dto.FleetTrackingUpdateDto event = objectMapper.readValue(message.getPayload(), com.fooddelivery.delivery.dto.FleetTrackingUpdateDto.class);
            String driverId = sessionUser;
            String payloadDriverId = event.getDriverId();
            
            if (payloadDriverId != null && !driverId.equals(payloadDriverId)) {
                log.warn("Driver ID mismatch: session user {}, payload driver {}", driverId, payloadDriverId);
                meterRegistry.counter("ws.telemetry.identity_mismatch").increment();
                return;
            }
            
            String orderId = event.getOrderId();
            if (orderId != null) {
                String activeOrder = redisTemplate.opsForValue().get("driver:active_order:" + driverId);
                if (!orderId.equals(activeOrder)) {
                    log.warn("Driver {} is not assigned to order {}, active is {}", driverId, orderId, activeOrder);
                    event.setOrderId(null); // Ignore the orderId for telemetry
                }
            }

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

    private void processBatch(List<com.fooddelivery.delivery.dto.FleetTrackingUpdateDto> batch) {
        if (batch.isEmpty()) return;
        log.info("Processing reactive telemetry batch of size {}", batch.size());
        try {
            for (com.fooddelivery.delivery.dto.FleetTrackingUpdateDto event : batch) {
                String driverId = event.getDriverId();
                Double latNum = event.getLat();
                Double lngNum = event.getLng();
                String orderId = event.getOrderId();
                if (driverId != null && latNum != null && lngNum != null) {
                    double lat = latNum;
                    double lng = lngNum;
                    String cityId = event.getCityId();
                    if (cityId == null) {
                        log.warn("Dropped telemetry event due to missing cityId: {}", driverId);
                        continue;
                    }
                    String geoKey = "drivers:geo:" + cityId;
                    // Update geospatial index
                    redisTemplate.opsForGeo().add(geoKey, new Point(lng, lat), driverId);
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

}
