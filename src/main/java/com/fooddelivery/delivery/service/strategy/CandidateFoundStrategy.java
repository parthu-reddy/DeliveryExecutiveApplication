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

    @Override
    public void process(JsonNode root, String eventType) throws Exception {
        UUID orderId = UUID.fromString(root.path("orderId").asText());
        String driverId = root.path("driverId").asText(null);
        log.info("Delivery Application received DISPATCH_CANDIDATE_FOUND for order {}. Driver {} will be pinged.", orderId, driverId);
        
        // Track the ping in Redis for timeout poller (30 seconds timeout)
        redisTemplate.opsForValue().set("order:ping:pending:" + orderId, driverId, Duration.ofSeconds(30));
        redisTemplate.opsForZSet().add("order:ping:timeouts", orderId.toString(), System.currentTimeMillis() + 30000);

        // Here we would typically push a notification to the driver's app over WebSockets.
        // For now, we simulate this by logging the action.
        log.info("Pinging Driver {} for Order {}...", driverId, orderId);
    }

    @Override
    public List<String> getEventTypes() {
        return Collections.singletonList(EventType.DISPATCH_CANDIDATE_FOUND);
    }
}
