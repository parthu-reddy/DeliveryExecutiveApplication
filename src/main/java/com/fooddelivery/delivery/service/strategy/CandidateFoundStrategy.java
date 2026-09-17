package com.fooddelivery.delivery.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fooddelivery.common.constants.EventType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class CandidateFoundStrategy implements DeliveryEventStrategy<com.fooddelivery.common.event.DispatchCandidateFoundEvent> {

    /** How long a rider has to accept. The endpoint and ACCEPT_SCRIPT both enforce this deadline. */
    public static final Duration PING_WINDOW = Duration.ofSeconds(60);

    /**
     * TTL for the ping bookkeeping keys. Deliberately much longer than {@link #PING_WINDOW}: the
     * timeout poller runs after the deadline, and must still find the pending set to release the
     * candidates. Never set this equal to the window.
     */
    public static final Duration PING_KEY_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final com.fooddelivery.common.service.NotificationRouterService notificationRouterService;
    private final com.fooddelivery.delivery.websocket.LocationTrackingWebSocketHandler locationTrackingWebSocketHandler;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Override
    public Class<com.fooddelivery.common.event.DispatchCandidateFoundEvent> eventClass() {
        return com.fooddelivery.common.event.DispatchCandidateFoundEvent.class;
    }

    @Override
    public void handle(com.fooddelivery.common.event.DispatchCandidateFoundEvent event, String eventType) throws Exception {
        UUID orderId = event.orderUuid();
        List<String> driverIds = new java.util.ArrayList<>();
        if (event.getDriverIds() != null) {
            for (java.util.UUID id : event.getDriverIds()) {
                driverIds.add(id.toString());
            }
        }
        log.info("Delivery Application received DISPATCH_CANDIDATE_FOUND for order {}. Drivers {} will be pinged.", orderId, driverIds);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId))) {
            log.info("Ignoring DISPATCH_CANDIDATE_FOUND for order {} as it is already locked (accepted/cancelled).", orderId);
            return;
        }
        // Track the ping in Redis for timeout poller
        String pendingPingKey = com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderId;
        redisTemplate.opsForSet().add(pendingPingKey, driverIds.toArray(new String[0]));
        // MUST outlive the ping window. DriverPingTimeoutPoller reads this set AFTER the window
        // closes; if it has expired, TIMEOUT_SCRIPT sees an empty set, returns EMPTY, and
        // timeoutOrderPing returns without releasing a single driver back to the Maps availability
        // pool -- which selection removed them from with an atomic SREM. A TTL equal to the window
        // loses that race every time and strands every driver who lets a ping lapse.
        // The TTL is garbage collection only; correctness comes from the explicit DEL in the Lua.
        redisTemplate.expire(pendingPingKey, PING_KEY_TTL);
        // Reset per dispatch cycle: a driver reached on an earlier cycle may be unreachable now.
        String reachedKey = com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_PING_REACHED + orderId;
        redisTemplate.delete(reachedKey);
        redisTemplate.opsForZSet().add(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS, orderId.toString(), System.currentTimeMillis() + PING_WINDOW.toMillis());
        // We intentionally do not reset failed cycles here, as candidates rejecting repeatedly should also eventually cause the order to fail and cancel, rather than looping infinitely.
        for (String driverId : driverIds) {
            redisTemplate.opsForValue().set(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVER_PENDING_PING + driverId, orderId.toString(), PING_KEY_TTL);
            log.info("Pinging Driver {} for Order {}...", driverId, orderId);

            // Send the ping directly through the active WebSocket connection, and record whether it
            // actually landed. timeoutOrderPing reads this to decide who genuinely ignored the
            // order: a driver with no live socket was never shown it, and counting that as a
            // rejection eventually excluded them from the order altogether.
            boolean reached = locationTrackingWebSocketHandler.sendPingToDriver(driverId, orderId.toString());
            if (reached) {
                redisTemplate.opsForSet().add(reachedKey, driverId);
            } else {
                // Distinct from the handler's ws.dispatch.* counters, which describe the socket.
                // This is the dispatch-level fact: this candidate has no CONFIRMED delivery, so a
                // lapsed ping will not count against them and the order relies on the push landing.
                meterRegistry.counter("dispatch.ping.unconfirmed").increment();
            }

            // Send a push notification to the driver as fallback
            com.fooddelivery.common.event.NotificationRequestEvent notificationEvent = com.fooddelivery.common.event.NotificationRequestEvent.builder().userId(UUID.fromString(driverId)).channel(com.fooddelivery.common.enums.ChannelType.PUSH).eventName(com.fooddelivery.common.constants.NotificationTemplate.NEW_ORDER_DISPATCH).templateParams(java.util.List.of(orderId.toString())).payload(java.util.Map.of("orderId", orderId.toString())).build();
            notificationRouterService.routeNotification(notificationEvent);
        }
        // Only after members exist: EXPIRE on a missing key is a no-op, so setting this before the
        // loop would leave the set with no TTL at all when nobody was reachable.
        if (Boolean.TRUE.equals(redisTemplate.hasKey(reachedKey))) {
            redisTemplate.expire(reachedKey, PING_KEY_TTL);
        }
    }

    @Override
    public List<String> getEventTypes() {
        return Collections.singletonList(EventType.DISPATCH_CANDIDATE_FOUND.name());
    }

}
