package com.fooddelivery.delivery.service;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Arrays;
import java.util.List;
import com.fooddelivery.common.constants.RedisKeyConstants;
import com.fooddelivery.delivery.enums.AssignmentResult;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderAssignmentService {

    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventHelper outboxEventHelper;
    private final IDeliveryExecutiveRepository repository;
    private final LogisticsDispatchService logisticsDispatchService;

    public String getPendingPing(UUID driverId) {
        return redisTemplate.opsForValue().get(RedisKeyConstants.PREFIX_DRIVER_PENDING_PING + driverId);
    }
    
    public Long getPingExpiration(UUID orderId) {
        Double score = redisTemplate.opsForZSet().score(RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS, orderId.toString());
        return score != null ? score.longValue() : null;
    }

    private static final String ACCEPT_SCRIPT = 
            "local pendingKey = KEYS[1]\n" +
            "local lockKey = KEYS[2]\n" +
            "local driverId = ARGV[1]\n" +
            "if redis.call('EXISTS', lockKey) == 1 then\n" +
            "    local lockVal = redis.call('GET', lockKey)\n" +
            "    if lockVal == 'CANCELLED' then return {'CANCELLED'} end\n" +
            "    return {'ALREADY_ACCEPTED'}\n" +
            "end\n" +
            "if redis.call('SISMEMBER', pendingKey, driverId) == 0 then return {'INVALID'} end\n" +
            "redis.call('SET', lockKey, driverId, 'EX', 86400)\n" +
            "local allPinged = redis.call('SMEMBERS', pendingKey)\n" +
            "redis.call('DEL', pendingKey)\n" +
            "if #allPinged == 0 then return {'SUCCESS_EMPTY'} end\n" +
            "return allPinged";

    public void acceptOrderPing(UUID driverId, UUID orderId) {
        log.info("Driver {} attempting to accept order {}", driverId, orderId);
        
        RedisScript<List> script = new DefaultRedisScript<>(ACCEPT_SCRIPT, List.class);
        List<String> result = redisTemplate.execute(script, Arrays.asList(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderId, RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId), driverId.toString());

        if (result == null) {
            throw new IllegalStateException("Order is no longer available.");
        }
        if (result.size() == 1 && AssignmentResult.CANCELLED.name().equals(result.get(0))) {
            log.warn("Order {} was cancelled. Driver {} cannot accept.", orderId, driverId);
            throw new IllegalStateException("Order was cancelled.");
        }
        if (result.size() == 1 && AssignmentResult.ALREADY_ACCEPTED.name().equals(result.get(0))) {
            log.warn("Order {} was already accepted by another driver. Driver {} ping rejected.", orderId, driverId);
            throw new IllegalStateException("Order is no longer available.");
        }
        if (result.size() == 1 && AssignmentResult.INVALID.name().equals(result.get(0))) {
            log.warn("Ping for order {} and driver {} is invalid or expired.", orderId, driverId);
            throw new IllegalStateException("Ping expired or invalid.");
        }
        
        try {
            transactionTemplate.executeWithoutResult(status -> {
                String currentLock = redisTemplate.opsForValue().get(RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId);
                if (!driverId.toString().equals(currentLock)) {
                    throw new IllegalStateException("Order lock was lost to cancellation. Aborting assignment.");
                }

                DeliveryExecutive executive = repository.findLockedById(driverId).orElseThrow();

                com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent = outboxEventHelper.createOutboxEvent(
                        com.fooddelivery.common.constants.AggregateType.ORDER,
                        orderId.toString(),
                        com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED,
                        java.util.Map.of(
                            "orderId", orderId.toString(),
                            "driverId", driverId.toString(),
                            "driverName", executive.getFullName(),
                            "driverPhone", executive.getPhoneNumber()
                        )
                );
                log.info("Triggering event: DRIVER_ASSIGNED for executive: {}", executive.getId());
                outboxEventRepository.save(outboxEvent);
                
                com.fooddelivery.delivery.service.state.DeliveryExecutiveState state = com.fooddelivery.delivery.service.state.DeliveryExecutiveStateFactory.getState(executive.getStatus());
                state.acceptOrder(executive);
                repository.save(executive);
                redisTemplate.opsForHash().put("drivers:status", driverId.toString(), executive.getStatus().name());
            });
            log.info("Driver {} accepted order {}. Emitted DRIVER_ASSIGNED event.", driverId, orderId);
            
            if (result.size() > 0 && !AssignmentResult.SUCCESS_EMPTY.name().equals(result.get(0))) {
                for (String pingedDriver : result) {
                    redisTemplate.delete(RedisKeyConstants.PREFIX_DRIVER_PENDING_PING + pingedDriver);
                    // NEW: Release lock for OTHER drivers who didn't win the race
                    if (!pingedDriver.equals(driverId.toString())) {
                        try {
                            logisticsDispatchService.releaseDriverLock(pingedDriver);
                        } catch (Exception e) {
                            log.error("Failed to release driver lock for driver {} on assignment, will be retried by availability poller", pingedDriver, e);
                        }
                    }
                }
            }
            redisTemplate.opsForZSet().remove(RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS, orderId.toString());
            
            // Set active order tracking
            redisTemplate.opsForValue().set(RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER + driverId, orderId.toString(), java.time.Duration.ofHours(24));
            
            try {
                String key = "drivers:available:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;
                redisTemplate.opsForSet().remove(key, driverId.toString());
            } catch (Exception e) {
                log.error("Failed to remove driver {} from Redis pool", driverId, e);
            }
        } catch (Exception e) {
            log.error("Failed to commit DRIVER_ASSIGNED transaction. Releasing Redis lock for order {}", orderId, e);
            String currentLock = redisTemplate.opsForValue().get(RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId);
            if (driverId.toString().equals(currentLock)) {
                redisTemplate.delete(RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId);
            }
            if (result != null && !result.isEmpty() && !AssignmentResult.SUCCESS_EMPTY.name().equals(result.get(0))) {
                redisTemplate.opsForSet().add(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderId, result.toArray(new String[0]));
                // Edge case: Add TTL to pending ping if we revert
                redisTemplate.expire(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderId, java.time.Duration.ofMinutes(5));
            }
            throw e;
        }
    }

    private static final String REJECT_SCRIPT = 
            "local pendingKey = KEYS[1]\n" +
            "local lockKey = KEYS[2]\n" +
            "local driverId = ARGV[1]\n" +
            "if redis.call('EXISTS', lockKey) == 1 then\n" +
            "    redis.call('SREM', pendingKey, driverId)\n" +
            "    return 'ACCEPTED_ALREADY'\n" +
            "end\n" +
            "local removed = redis.call('SREM', pendingKey, driverId)\n" +
            "if removed == 0 then return 'NOT_FOUND' end\n" +
            "local remaining = redis.call('SCARD', pendingKey)\n" +
            "if remaining == 0 then return 'LAST_REJECT' end\n" +
            "return 'REJECTED'";

    public void rejectOrderPing(UUID driverId, UUID orderId) {
        log.info("Driver {} rejected order ping {}", driverId, orderId);
        
        RedisScript<String> script = new DefaultRedisScript<>(REJECT_SCRIPT, String.class);
        String result = redisTemplate.execute(script, Arrays.asList(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderId, RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId), driverId.toString());

        redisTemplate.delete(RedisKeyConstants.PREFIX_DRIVER_PENDING_PING + driverId);
        redisTemplate.opsForHash().increment(RedisKeyConstants.PREFIX_ORDER_REJECTED_DRIVERS + orderId, driverId.toString(), 1);
        redisTemplate.expire(RedisKeyConstants.PREFIX_ORDER_REJECTED_DRIVERS + orderId, java.time.Duration.ofHours(2));
        try {
            log.info("Releasing driver lock for driver {} after rejection so they can receive future dispatches...", driverId);
            logisticsDispatchService.releaseDriverLock(driverId.toString());
        } catch (Exception e) {
            log.error("Failed to release driver lock for driver {} on reject, will be retried by availability poller", driverId, e);
        }

        if (AssignmentResult.LAST_REJECT.name().equals(result)) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent = outboxEventHelper.createOutboxEvent(
                            com.fooddelivery.common.constants.AggregateType.ORDER,
                            orderId.toString(),
                            com.fooddelivery.common.constants.EventType.ORDER_DRIVER_REJECTED,
                            java.util.Map.of(
                                "orderId", orderId.toString(),
                                "driverId", driverId.toString()
                            )
                    );
                    log.info("Triggering event: ORDER_DRIVER_REJECTED for aggregate: {}", orderId);
                    outboxEventRepository.save(outboxEvent);
                });
                redisTemplate.opsForZSet().remove(RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS, orderId.toString());
            } catch (Exception e) {
                log.error("Failed to save ORDER_DRIVER_REJECTED event to outbox. Reverting Redis state for order {}", orderId, e);
                redisTemplate.opsForSet().add(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderId, driverId.toString());
                redisTemplate.expire(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderId, java.time.Duration.ofMinutes(5));
                throw e;
            }
        }
    }

    private static final String TIMEOUT_SCRIPT =
            "local pendingKey = KEYS[1]\n" +
            "local lockKey = KEYS[2]\n" +
            "if redis.call('EXISTS', lockKey) == 1 then\n" +
            "    redis.call('DEL', pendingKey)\n" +
            "    return {'ALREADY_ACCEPTED'}\n" +
            "end\n" +
            "local pendingDrivers = redis.call('SMEMBERS', pendingKey)\n" +
            "if #pendingDrivers == 0 then return {'EMPTY'} end\n" +
            "redis.call('DEL', pendingKey)\n" +
            "return pendingDrivers";

    public void timeoutOrderPing(UUID orderId) {
        log.info("Order ping timed out for order {}", orderId);
        String orderIdStr = orderId.toString();

        RedisScript<List> script = new DefaultRedisScript<>(TIMEOUT_SCRIPT, List.class);
        List<String> result = redisTemplate.execute(script, Arrays.asList(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderIdStr, RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderIdStr));

        if (result == null || result.isEmpty()) {
            redisTemplate.opsForZSet().remove(RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS, orderIdStr);
            return;
        }

        if (AssignmentResult.ALREADY_ACCEPTED.name().equals(result.get(0)) || AssignmentResult.EMPTY.name().equals(result.get(0))) {
            log.info("Order {} ping phase already finished (status: {}). Cleaning up orphan timeout.", orderId, result.get(0));
            redisTemplate.opsForZSet().remove(RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS, orderIdStr);
            return;
        }

        log.info("Order {} ping timed out for {} drivers: {}", orderId, result.size(), result);
        for (String driverIdStr : result) {
            redisTemplate.delete(RedisKeyConstants.PREFIX_DRIVER_PENDING_PING + driverIdStr);
            redisTemplate.opsForHash().increment(RedisKeyConstants.PREFIX_ORDER_REJECTED_DRIVERS + orderIdStr, driverIdStr, 1);
            redisTemplate.expire(RedisKeyConstants.PREFIX_ORDER_REJECTED_DRIVERS + orderIdStr, java.time.Duration.ofHours(2));
            try {
                log.info("Releasing driver lock for driver {} after timeout so they can receive future dispatches...", driverIdStr);
                logisticsDispatchService.releaseDriverLock(driverIdStr);
            } catch (Exception e) {
                log.error("Failed to release driver lock for driver {} on timeout, will be retried by availability poller", driverIdStr, e);
            }
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent = outboxEventHelper.createOutboxEvent(
                        com.fooddelivery.common.constants.AggregateType.ORDER,
                        orderIdStr,
                        com.fooddelivery.common.constants.EventType.ORDER_DRIVER_REJECTED,
                        java.util.Map.of(
                            "orderId", orderIdStr,
                            "driverId", result.get(0)
                        )
                );
                log.info("Triggering event: ORDER_DRIVER_REJECTED for aggregate: {}", orderIdStr);
                outboxEventRepository.save(outboxEvent);
            });
            redisTemplate.opsForZSet().remove(RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS, orderIdStr);
        } catch (Exception e) {
            log.error("Failed to save ORDER_DRIVER_REJECTED event to outbox. Reverting Redis state for order {}", orderIdStr, e);
            if (result != null && !result.isEmpty()) {
                redisTemplate.opsForSet().add(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderIdStr, result.toArray(new String[0]));
                redisTemplate.expire(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderIdStr, java.time.Duration.ofMinutes(5));
            }
            throw e;
        }
    }
    
    public void forceAssignOrder(UUID orderId, UUID driverId) {
        log.info("Admin forcing assignment of order {} to driver {}", orderId, driverId);
        
        // Clean up ALL pending pings for drivers that were being pinged
        java.util.Set<String> pendingDrivers = redisTemplate.opsForSet().members(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderId);
        redisTemplate.delete(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderId);
        if (pendingDrivers != null) {
            for (String pendingDriverId : pendingDrivers) {
                redisTemplate.delete(RedisKeyConstants.PREFIX_DRIVER_PENDING_PING + pendingDriverId);
                if (!pendingDriverId.equals(driverId.toString())) {
                    try {
                        logisticsDispatchService.releaseDriverLock(pendingDriverId);
                    } catch (Exception e) {
                        log.error("Failed to release driver lock for driver {} on force assignment", pendingDriverId, e);
                    }
                }
            }
        }
        redisTemplate.opsForZSet().remove(RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS, orderId.toString());
        redisTemplate.opsForZSet().remove("delayed_dispatch_queue", orderId.toString());

        // Force set the order driver lock
        redisTemplate.opsForValue().set(RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId, driverId.toString(), java.time.Duration.ofHours(24));
        redisTemplate.opsForValue().set(RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER + driverId, orderId.toString(), java.time.Duration.ofHours(24));
        // Prevent stale ORDER_DRIVER_REJECTED events from re-dispatching after force-assign
        redisTemplate.opsForValue().set(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_LOCK + orderId, "CANCELLED", java.time.Duration.ofHours(24));

        try {
            transactionTemplate.executeWithoutResult(status -> {
                DeliveryExecutive executive = repository.findLockedById(driverId).orElseThrow(() -> new IllegalArgumentException("Driver not found"));
                
                com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent = outboxEventHelper.createOutboxEvent(
                        com.fooddelivery.common.constants.AggregateType.ORDER,
                        orderId.toString(),
                        com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED,
                        java.util.Map.of(
                            "orderId", orderId.toString(),
                            "driverId", driverId.toString(),
                            "driverName", executive.getFullName(),
                            "driverPhone", executive.getPhoneNumber()
                        )
                );
                log.info("Triggering event: DRIVER_ASSIGNED for executive: {}", driverId);
                outboxEventRepository.save(outboxEvent);
                
                com.fooddelivery.delivery.service.state.DeliveryExecutiveState state = com.fooddelivery.delivery.service.state.DeliveryExecutiveStateFactory.getState(executive.getStatus());
                
                if (executive.getStatus() == com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.OFFLINE) {
                     executive.setStatus(com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.ONLINE);
                }
                
                state = com.fooddelivery.delivery.service.state.DeliveryExecutiveStateFactory.getState(executive.getStatus());
                state.acceptOrder(executive);
                repository.save(executive);
                redisTemplate.opsForHash().put("drivers:status", driverId.toString(), executive.getStatus().name());
            });
        } catch (Exception e) {
            log.error("Failed to commit DRIVER_ASSIGNED transaction in forceAssignOrder. Releasing Redis lock for order {}", orderId, e);
            redisTemplate.delete(RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId);
            throw e;
        }
        
        try {
            String key = "drivers:available:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;
            redisTemplate.opsForSet().remove(key, driverId.toString());
        } catch (Exception e) {
            log.error("Failed to remove driver {} from Redis pool", driverId, e);
        }
    }
}
