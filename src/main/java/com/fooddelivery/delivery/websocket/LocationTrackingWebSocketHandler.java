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
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import reactor.core.publisher.Sinks;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Component
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class LocationTrackingWebSocketHandler extends TextWebSocketHandler {
private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WebSocketSession> userSessions = new ConcurrentHashMap<>();
    private final Sinks.Many<TelemetryEvent> telemetrySink = Sinks.many().multicast().onBackpressureBuffer();

    /**
     * Drains the telemetry sink into Redis in batches. Without this subscription
     * {@link #handleTextMessage} emits into a sink nobody reads and every driver
     * location update is silently discarded.
     */
    @jakarta.annotation.PostConstruct
    void subscribeToTelemetrySink() {
        telemetrySink.asFlux()
            .onBackpressureDrop(event -> {
                log.warn("Dropped telemetry event due to backpressure: {}", event.driverId());
                meterRegistry.counter("ws.telemetry.dropped").increment();
            })
            .bufferTimeout(50, Duration.ofSeconds(1))
            .publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
            .subscribe(this::flushBatch);
    }

    private void flushBatch(java.util.List<TelemetryEvent> batch) {
        for (TelemetryEvent event : batch) {
            try {
                if (event.cityId() == null || event.cityId().isEmpty()) {
                    log.warn("Dropped telemetry event due to missing cityId: {}", event.driverId());
                    meterRegistry.counter("ws.telemetry.missing_city").increment();
                    continue;
                }
                redisTemplate.opsForGeo().add("drivers:geo:" + event.cityId(),
                        new Point(event.lng(), event.lat()), event.driverId());
                redisTemplate.opsForZSet().add("driver_last_ping", event.driverId(),
                        System.currentTimeMillis());
                if (event.orderId() != null && !event.orderId().isEmpty()) {
                    redisTemplate.convertAndSend("tracking:order:" + event.orderId(),
                            objectMapper.writeValueAsString(event));
                }
            } catch (Exception e) {
                log.error("Failed to flush telemetry for driver {}", event.driverId(), e);
            }
        }
    }

    private static final int SEND_TIME_LIMIT_MS = 5000;
    private static final int BUFFER_SIZE_BYTES = 512 * 1024;

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
        WebSocketSession guarded = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_BYTES);
        String sessionId = guarded.getId();
        activeSessions.put(sessionId, guarded);
        userSessions.put(userId, guarded);
        log.info("WebSocket connected: {} for user: {}", sessionId, userId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        try {
            String sessionUser = (String) session.getAttributes().get("userId");
            JsonNode jsonNode = objectMapper.readTree(payload);
            String driverId = sessionUser;
            String payloadDriverId = jsonNode.has("driverId") ? jsonNode.get("driverId").asText() : null;
            
            if (payloadDriverId != null && !driverId.equals(payloadDriverId)) {
                log.warn("Driver ID mismatch: session user {}, payload driver {}", driverId, payloadDriverId);
                meterRegistry.counter("ws.telemetry.identity_mismatch").increment();
                return;
            }
            
            double lat = jsonNode.get("lat").asDouble();
            double lng = jsonNode.get("lng").asDouble();
            String orderId = jsonNode.has("orderId") ? jsonNode.get("orderId").asText() : null;
            String cityId = jsonNode.has("cityId") ? jsonNode.get("cityId").asText() : null;
            
            if (orderId != null) {
                String activeOrder = redisTemplate.opsForValue().get("driver:active_order:" + driverId);
                if (!orderId.equals(activeOrder)) {
                    log.warn("Driver {} is not assigned to order {}, active is {}", driverId, orderId, activeOrder);
                    orderId = null; // Ignore the orderId for telemetry
                }
            }

            // Emit to the sink (reactive)
            telemetrySink.tryEmitNext(new TelemetryEvent(driverId, orderId, lat, lng, cityId));
        } catch (Exception e) {
            log.error("TELEMETRY_PAYLOAD_REJECTED sessionId={} payloadBytes={} errorType={} error={}",
                    session.getId(), payload == null ? 0 : payload.length(),
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        activeSessions.remove(session.getId());
        String userId = (String) session.getAttributes().get("userId");
        if (userId != null) {
            // Compare by session id, not by reference. The map holds the
            // ConcurrentWebSocketSessionDecorator created in afterConnectionEstablished, while the
            // container hands this callback the RAW session; the decorator does not override
            // equals, so remove(userId, session) never matches and the entry leaked on every close.
            // Still conditional: a driver who reconnects before this callback fires must keep their
            // NEW session, which a plain remove(userId) would drop.
            userSessions.computeIfPresent(userId,
                    (key, stored) -> stored.getId().equals(session.getId()) ? null : stored);
        }
        log.info("WebSocket closed: {} for user: {} with code: {} reason: {}", session.getId(), userId, status.getCode(), status.getReason());
    }

    /** Live driver sessions. Exposed for SessionRegistryLifecycleTest, which pins that a
     *  closed socket is actually removed -- the decorator broke reference equality once already. */
    int sessionCountForTest() {
        return userSessions.size();
    }

    /**
     * Publishes a dispatch ping to whichever instance holds this driver's socket.
     *
     * @return true only when the ping reached a live socket. The caller records this: a driver who
     *         was never shown the order must not be counted as having rejected it when the ping
     *         window lapses. A push notification is also sent by the caller, but the notification
     *         router reports acceptance, not delivery, so it cannot answer this question.
     */
    public boolean sendPingToDriver(String driverId, String orderId) {
        try {
            java.util.Map<String, String> payload = java.util.Map.of("type", "NEW_ORDER_DISPATCH", "orderId", orderId);
            String message = objectMapper.writeValueAsString(payload);
            Long subscribers = redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Long>) connection -> 
                connection.publish(("ws:driver:" + driverId).getBytes(java.nio.charset.StandardCharsets.UTF_8), message.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            );
            
            if (subscribers != null && subscribers > 0) {
                log.info("Published ping to driver {} to {} instances", driverId, subscribers);
                return true;
            } else {
                Double lastPing = redisTemplate.opsForZSet().score("driver_last_ping", driverId);
                boolean isOffline = lastPing == null || (System.currentTimeMillis() - lastPing > 30000);
                if (isOffline) {
                    log.warn("Driver {} is offline, cannot send ping", driverId);
                    meterRegistry.counter("ws.dispatch.driver_offline").increment();
                } else {
                    log.warn("Driver {} is active but no instance holds this socket", driverId);
                    meterRegistry.counter("ws.dispatch.no_socket").increment();
                }
            }
        } catch (Exception e) {
            log.error("Failed to publish ping for driver {}", driverId, e);
        }
        return false;
    }

    public void handleRedisPing(String message, String channel) {
        String driverId = channel.replace("ws:driver:", "");
        WebSocketSession session = userSessions.get(driverId);
        if (session != null && session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(message));
                log.info("Successfully sent ping to driver {} via WebSocket", driverId);
            } catch (Exception e) {
                log.error("Failed to send ping to driver {} via WebSocket", driverId, e);
                meterRegistry.counter("ws.dispatch.send_failed").increment();
            }
        }
    }


    private record TelemetryEvent(String driverId, String orderId, double lat, double lng, String cityId) {
    }
}
