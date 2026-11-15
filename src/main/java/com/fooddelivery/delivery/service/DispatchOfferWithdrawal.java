package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Takes a dispatch offer back off a rider's screen the moment it stops being theirs to take.
 *
 * <p>An offer goes to every candidate dispatch selected ({@code CandidateFoundStrategy}); the
 * first to accept wins. The losers' {@code driver:pending_ping} keys were deleted, but nothing told
 * their apps, which kept showing "New Dispatch" until their next 5-second poll. A rider who tapped
 * Accept in that window got HTTP 409 (FR-2, KE-7 on Dev 2026-11-14). The same holds when an admin
 * force-assigns the order or it is cancelled while the offer is open.
 *
 * <p>Publishes on {@code ws:driver:{id}}, the channel dispatch pings and duty changes already use;
 * the instance holding the rider's socket forwards it verbatim. Best effort: the rider app's poll
 * of {@code /available} still drops the offer if this message is lost.
 */
@Component
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class DispatchOfferWithdrawal {
    public static final String MESSAGE_TYPE = "DISPATCH_OFFER_WITHDRAWN";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** Tells {@code driverId}'s app that its offer for {@code orderId} is gone. Never throws. */
    public void withdraw(String driverId, UUID orderId) {
        try {
            redisTemplate.convertAndSend("ws:driver:" + driverId, message(orderId));
        } catch (Exception e) {
            log.warn("DISPATCH_OFFER_WITHDRAW_FAILED driverId={} orderId={}", driverId, orderId, e);
        }
    }

    public String message(UUID orderId) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("type", MESSAGE_TYPE, "orderId", orderId.toString()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise a two-string map", e);
        }
    }
}
