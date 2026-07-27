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
        
        List<String> driverIds = new java.util.ArrayList<>();
        JsonNode driverIdsNode = root.path("driverIds");
        if (driverIdsNode.isArray()) {
            for (JsonNode idNode : driverIdsNode) {
                driverIds.add(idNode.asText());
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
        redisTemplate.expire(pendingPingKey, Duration.ofSeconds(60));
        redisTemplate.opsForZSet().add(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS, orderId.toString(), System.currentTimeMillis() + 60000);

        for (String driverId : driverIds) {
            redisTemplate.opsForValue().set(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVER_PENDING_PING + driverId, orderId.toString(), Duration.ofSeconds(60));
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
    }

    @Override
    public List<String> getEventTypes() {
        return Collections.singletonList(EventType.DISPATCH_CANDIDATE_FOUND.name());
    }
}
