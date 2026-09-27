package com.fooddelivery.delivery.service.duty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.enums.DutyChangeReason;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Tells a rider's app what the server now believes their duty status is.
 *
 * <p>The app used to learn its status once, from {@code /api/delivery/profile} at login, and then
 * only from its own button presses. When the stale-driver sweeper demoted a rider whose location
 * had stopped arriving, the app kept showing "Online Duty" to someone dispatch could no longer see.
 *
 * <p>Publishes on {@code ws:driver:{id}}, the channel dispatch pings already use; every instance
 * subscribes to that pattern and the one holding the rider's socket forwards the message verbatim.
 * Best effort: a rider whose socket is down gets the same answer as a snapshot when it reconnects.
 */
@Component
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class RiderDutyNotifier {
    public static final String MESSAGE_TYPE = "DUTY_STATUS";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void publish(UUID driverId, DeliveryExecutiveStatus status, DutyChangeReason reason) {
        try {
            redisTemplate.convertAndSend("ws:driver:" + driverId, message(status, reason));
        } catch (Exception e) {
            log.warn("DUTY_STATUS_PUBLISH_FAILED driverId={} status={} reason={}", driverId, status, reason, e);
        }
    }

    public String message(DeliveryExecutiveStatus status, DutyChangeReason reason) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("type", MESSAGE_TYPE, "status", status.name(), "reason", reason.name()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise a three-string map", e);
        }
    }
}
