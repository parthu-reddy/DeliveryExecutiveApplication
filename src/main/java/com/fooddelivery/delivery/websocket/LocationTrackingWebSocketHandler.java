package com.fooddelivery.delivery.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.publisher.Sinks;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LocationTrackingWebSocketHandler extends TextWebSocketHandler {
    @java.lang.SuppressWarnings("all")
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LocationTrackingWebSocketHandler.class);
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final ConcurrentHashMap<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WebSocketSession> userSessions = new ConcurrentHashMap<>();
    private final Sinks.Many<TelemetryEvent> telemetrySink = Sinks.many().multicast().onBackpressureBuffer();
    private static final String DRIVER_LOCATION_KEY = "drivers:geo:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;

    public LocationTrackingWebSocketHandler(ObjectMapper objectMapper, StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        // Reactive stream processing with batching/buffering
        telemetrySink.asFlux().onBackpressureDrop(event -> log.warn("Dropped telemetry event due to backpressure: {}", event.driverId())).bufferTimeout(50, Duration.ofSeconds(1)).subscribe(batch -> {
            try {
                batch.forEach(event -> {
                    // Update geospatial index
                    redisTemplate.opsForGeo().add(DRIVER_LOCATION_KEY, new Point(event.lng, event.lat), event.driverId);
                    // Track the last ping time in a ZSET for stale driver detection
                    redisTemplate.opsForZSet().add("driver_last_ping", event.driverId, System.currentTimeMillis());
                    // Publish to pub/sub for SSE tracking
                    if (event.orderId != null && !event.orderId.isEmpty()) {
                        try {
                            String channel = "tracking:order:" + event.orderId;
                            redisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(event));
                        } catch (Exception e) {
                            log.error("Failed to publish to channel", e);
                        }
                    }
                });
                log.debug("Flushed {} location updates to Redis", batch.size());
            } catch (Exception e) {
                log.error("Failed to flush locations to Redis", e);
            }
        });
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = (String) session.getAttributes().get("userId");
        if (userId == null) {
            log.warn("Unauthorized WebSocket connection attempt (missing userId in session): {}", session.getId());
            try {
                session.close(CloseStatus.NOT_ACCEPTABLE);
            } catch (Exception e) {
                log.error("Failed to close unauthenticated session", e);
            }
            return;
        }
        String sessionId = session.getId();
        activeSessions.put(sessionId, session);
        userSessions.put(userId, session);
        log.info("WebSocket connected: {} for user: {}", sessionId, userId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        try {
            JsonNode jsonNode = objectMapper.readTree(payload);
            String driverId = jsonNode.get("driverId").asText();
            double lat = jsonNode.get("lat").asDouble();
            double lng = jsonNode.get("lng").asDouble();
            String orderId = jsonNode.has("orderId") ? jsonNode.get("orderId").asText() : null;
            // Emit to the sink (reactive)
            telemetrySink.tryEmitNext(new TelemetryEvent(driverId, orderId, lat, lng));
        } catch (Exception e) {
            log.error("Failed to parse telemetry payload: {}", payload, e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        activeSessions.remove(session.getId());
        String userId = (String) session.getAttributes().get("userId");
        if (userId != null) {
            userSessions.remove(userId, session);
        }
        log.info("WebSocket closed: {}", session.getId());
    }

    public void sendPingToDriver(String driverId, String orderId) {
        WebSocketSession session = userSessions.get(driverId);
        if (session != null && session.isOpen()) {
            try {
                java.util.Map<String, String> payload = java.util.Map.of("type", "NEW_ORDER_DISPATCH", "orderId", orderId);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
                log.info("Successfully sent ping to driver {} via WebSocket", driverId);
            } catch (Exception e) {
                log.error("Failed to send ping to driver {} via WebSocket", driverId, e);
            }
        } else {
            log.warn("Driver {} is not connected via WebSocket, cannot send ping", driverId);
        }
    }


    private record TelemetryEvent(String driverId, String orderId, double lat, double lng) {
    }
}
