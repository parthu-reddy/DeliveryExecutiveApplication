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

    private final org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 5000)
    public void pollDelayedDispatches() {
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(com.fooddelivery.common.constants.RedisKeyConstants.LOCK_POLL_DELAYED_DISPATCHES, "1", java.time.Duration.ofSeconds(4));
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        
        Set<String> orderIds = redisTemplate.opsForZSet().rangeByScore("delayed_dispatch_queue", 0, currentTime);
        
        if (orderIds != null && !orderIds.isEmpty()) {
            log.info("DelayedDispatchPoller found {} orders due for dispatch.", orderIds.size());
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
                    
                    String failedCyclesStr = redisTemplate.opsForValue().get("order:dispatch_failed_cycles:" + orderIdStr);
                    int failedCycles = failedCyclesStr != null ? Integer.parseInt(failedCyclesStr) : 0;
                    log.info("Processing delayed dispatch for order {}. dispatch_failed_cycles={}", orderIdStr, failedCycles);
                    if (failedCycles >= 5) {
                        log.error("Order {} failed to find any drivers 5 consecutive times. Emitting MANUAL_INTERVENTION_REQUIRED", orderIdStr);
                        redisTemplate.opsForZSet().remove("delayed_dispatch_queue", orderIdStr);
                        redisTemplate.delete("order:dispatch_failed_cycles:" + orderIdStr);
                        
                        java.util.Map<String, Object> eventPayload = java.util.Map.of(
                                "orderId", orderIdStr,
                                "eventType", com.fooddelivery.common.constants.EventType.MANUAL_INTERVENTION_REQUIRED.name()
                        );
                        
                        org.springframework.messaging.Message<String> message = org.springframework.messaging.support.MessageBuilder
                                .withPayload(objectMapper.writeValueAsString(eventPayload))
                                .setHeader(org.springframework.kafka.support.KafkaHeaders.TOPIC, com.fooddelivery.common.constants.KafkaConstants.TOPIC_ORDER_EVENTS)
                                .setHeader(org.springframework.kafka.support.KafkaHeaders.KEY, orderIdStr)
                                .setHeader("eventType", com.fooddelivery.common.constants.EventType.MANUAL_INTERVENTION_REQUIRED.name())
                                .build();
                        
                        kafkaTemplate.send(message);
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
                            java.util.List<String> excludedDriverIds = new java.util.ArrayList<>();
                            java.util.Map<Object, Object> rejections = redisTemplate.opsForHash().entries(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_REJECTED_DRIVERS + orderIdStr);
                            
                            if (rejections != null && !rejections.isEmpty()) {
                                for (java.util.Map.Entry<Object, Object> entry : rejections.entrySet()) {
                                    String driverId = entry.getKey().toString();
                                    int count = Integer.parseInt(entry.getValue().toString());
                                    if (count >= 5) {
                                        excludedDriverIds.add(driverId);
                                    }
                                }
                            }
                            log.info("Delayed dispatch triggered for order {}. excludedDriverIds={}. Dispatching nearest driver...", orderIdStr, excludedDriverIds);
                            
                            logisticsDispatchService.dispatchNearestDriver(lat, lng, deliveryLat, deliveryLng, deliveryAddress, UUID.fromString(orderIdStr), excludedDriverIds);
                        }
                    } else {
                        log.warn("No dispatch payload found for order {} in delayed queue. Removing stale entry.", orderIdStr);
                        redisTemplate.opsForZSet().remove("delayed_dispatch_queue", orderIdStr);
                    }

                    // Remove from queue ONLY after successful processing
                    redisTemplate.opsForZSet().remove("delayed_dispatch_queue", orderIdStr);

                } catch (Exception e) {
                    log.error("Failed to process delayed dispatch for order {}", orderIdStr, e);
                } finally {
                    String lockKey = "dispatch_processing_lock:" + orderIdStr;
                    redisTemplate.delete(lockKey);
                }
            }
        }
    }
}
