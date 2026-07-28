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
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(com.fooddelivery.common.constants.RedisKeyConstants.LOCK_POLL_DELAYED_DISPATCHES, "1", java.time.Duration.ofSeconds(4));
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        
        Set<String> orderIds = redisTemplate.opsForZSet().rangeByScore("delayed_dispatch_queue", 0, currentTime);
        
        if (orderIds != null && !orderIds.isEmpty()) {
            for (String orderIdStr : orderIds) {
                try {
                    // Try to acquire a processing lock for this order
                    String lockKey = "dispatch_processing_lock:" + orderIdStr;
                    Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", java.time.Duration.ofSeconds(30));
                    if (!Boolean.TRUE.equals(acquired)) {
                        continue; // Someone else is processing it
                    }

                    // Guard: check if order was cancelled while waiting in the delayed queue
                    String dispatchLock = redisTemplate.opsForValue().get(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_LOCK + orderIdStr);
                    if ("CANCELLED".equals(dispatchLock)) {
                        log.info("Order {} was cancelled while in delayed dispatch queue. Skipping.", orderIdStr);
                        redisTemplate.opsForZSet().remove("delayed_dispatch_queue", orderIdStr);
                        continue;
                    }
                    
                    String payload = redisTemplate.opsForValue().get(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_PAYLOAD + orderIdStr);
                    if (payload != null) {
                        JsonNode root = objectMapper.readTree(payload);
                        double lat = root.path("restaurantLat").asDouble(0.0);
                        double lng = root.path("restaurantLng").asDouble(0.0);
                        double deliveryLat = root.path("deliveryLat").asDouble(0.0);
                        double deliveryLng = root.path("deliveryLng").asDouble(0.0);
                        String deliveryAddress = root.path("deliveryAddress").asText("");
                        
                        if (lat != 0.0 && lng != 0.0) {
                            log.info("Delayed dispatch triggered for order {}. Dispatching nearest driver...", orderIdStr);
                            
                            java.util.Set<String> rejectedDrivers = redisTemplate.opsForSet().members(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_REJECTED_DRIVERS + orderIdStr);
                            java.util.List<String> excludedDriverIds = rejectedDrivers != null ? new java.util.ArrayList<>(rejectedDrivers) : null;
                            
                            logisticsDispatchService.dispatchNearestDriver(lat, lng, deliveryLat, deliveryLng, deliveryAddress, UUID.fromString(orderIdStr), excludedDriverIds);
                        }
                    }

                    // Remove from queue ONLY after successful processing
                    redisTemplate.opsForZSet().remove("delayed_dispatch_queue", orderIdStr);

                } catch (Exception e) {
                    log.error("Failed to process delayed dispatch for order {}", orderIdStr, e);
                }
            }
        }
    }
}
