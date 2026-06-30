package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class DelayedDispatchPoller {

    private final StringRedisTemplate redisTemplate;
    private final LogisticsDispatchService logisticsDispatchService;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 5000)
    public void pollDelayedDispatches() {
        long currentTime = System.currentTimeMillis();
        
        Set<String> orderIds = redisTemplate.opsForZSet().rangeByScore("delayed_dispatch_queue", 0, currentTime);
        
        if (orderIds != null && !orderIds.isEmpty()) {
            for (String orderIdStr : orderIds) {
                try {
                    String payload = redisTemplate.opsForValue().get("order:dispatchPayload:" + orderIdStr);
                    if (payload != null) {
                        JsonNode root = objectMapper.readTree(payload);
                        double lat = root.path("restaurantLat").asDouble(0.0);
                        double lng = root.path("restaurantLng").asDouble(0.0);
                        
                        if (lat != 0.0 && lng != 0.0) {
                            log.info("Delayed dispatch triggered for order {}. Dispatching nearest driver...", orderIdStr);
                            logisticsDispatchService.dispatchNearestDriver(lat, lng, UUID.fromString(orderIdStr));
                        }
                        
                        // Cleanup
                        redisTemplate.delete("order:dispatchPayload:" + orderIdStr);
                    }
                    redisTemplate.opsForZSet().remove("delayed_dispatch_queue", orderIdStr);
                } catch (Exception e) {
                    log.error("Failed to process delayed dispatch for order {}", orderIdStr, e);
                }
            }
        }
    }
}
