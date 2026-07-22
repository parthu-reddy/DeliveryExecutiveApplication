package com.fooddelivery.delivery.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fooddelivery.common.constants.EventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class CandidateFoundStrategy implements DeliveryEventStrategy {

    private final StringRedisTemplate redisTemplate;
    private final com.fooddelivery.common.service.NotificationRouterService notificationRouterService;
    private final org.springframework.core.env.Environment env;
    private final com.fooddelivery.delivery.websocket.LocationTrackingWebSocketHandler locationTrackingWebSocketHandler;

    @Override
    public void process(JsonNode root, String eventType) throws Exception {
        UUID orderId = UUID.fromString(root.path("orderId").asText());
        String driverId = root.path("driverId").asText(null);
        log.info("Delivery Application received DISPATCH_CANDIDATE_FOUND for order {}. Driver {} will be pinged.", orderId, driverId);
        
        // Track the ping in Redis for timeout poller (30 seconds timeout limit for driver to accept, but 60s TTL for the key to avoid race conditions with poller)
        redisTemplate.opsForValue().set("order:ping:pending:" + orderId, driverId, Duration.ofSeconds(60));
        redisTemplate.opsForValue().set("driver:pending_ping:" + driverId, orderId.toString(), Duration.ofSeconds(60));
        redisTemplate.opsForZSet().add("order:ping:timeouts", orderId.toString(), System.currentTimeMillis() + 30000);

        log.info("Pinging Driver {} for Order {}...", driverId, orderId);

        if (env.acceptsProfiles(org.springframework.core.env.Profiles.of("dev"))) {
            // For local development, send the ping directly through the active WebSocket connection
            locationTrackingWebSocketHandler.sendPingToDriver(driverId, orderId.toString());
        } else {
            // Send a push notification to the driver
            com.fooddelivery.common.event.NotificationRequestEvent notificationEvent = com.fooddelivery.common.event.NotificationRequestEvent.builder()
                    .userId(UUID.fromString(driverId))
                    .channel(com.fooddelivery.common.enums.ChannelType.PUSH)
                    .eventName("NEW_ORDER_DISPATCH")
                    .payload(java.util.Map.of("orderId", orderId.toString()))
                    .build();
            notificationRouterService.routeNotification(notificationEvent);
        }
    }

    @Override
    public List<String> getEventTypes() {
        return Collections.singletonList(EventType.DISPATCH_CANDIDATE_FOUND.name());
    }
}
