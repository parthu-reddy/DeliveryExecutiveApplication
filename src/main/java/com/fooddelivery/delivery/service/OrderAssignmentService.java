package com.fooddelivery.delivery.service;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
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
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class OrderAssignmentService {
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventHelper outboxEventHelper;
    private final IDeliveryExecutiveRepository repository;
    private final LogisticsDispatchService logisticsDispatchService;
    private final com.fooddelivery.delivery.repository.OrderAssignmentRepository assignmentRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.fooddelivery.delivery.client.CustomerServiceClient customerServiceClient;

    public String getPendingPing(UUID driverId) {
        return redisTemplate.opsForValue().get(RedisKeyConstants.PREFIX_DRIVER_PENDING_PING + driverId);
    }

    public Long getPingExpiration(UUID orderId) {
        Double score = redisTemplate.opsForZSet().score(RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS, orderId.toString());
        return score != null ? score.longValue() : null;
    }

    private static final String ACCEPT_SCRIPT = "local pendingKey = KEYS[1]\n"
            + "local lockKey = KEYS[2]\n"
            + "local timeoutKey = KEYS[3]\n"
            + "local driverId = ARGV[1]\n"
            + "local orderId = ARGV[2]\n"
            + "local nowMillis = tonumber(ARGV[3])\n"
            + "if redis.call('EXISTS', lockKey) == 1 then\n"
            + "    local lockVal = redis.call('GET', lockKey)\n"
            + "    if lockVal == 'CANCELLED' then return {'CANCELLED'} end\n"
            + "    return {'ALREADY_ACCEPTED'}\n"
            + "end\n"
            + "local expiresAt = redis.call('ZSCORE', timeoutKey, orderId)\n"
            + "if not expiresAt or tonumber(expiresAt) <= nowMillis then return {'EXPIRED'} end\n"
            + "if redis.call('SISMEMBER', pendingKey, driverId) == 0 then return {'INVALID'} end\n"
            + "redis.call('SET', lockKey, driverId, 'EX', 86400)\n"
            + "local allPinged = redis.call('SMEMBERS', pendingKey)\n"
            + "redis.call('DEL', pendingKey)\n"
            + "redis.call('ZREM', timeoutKey, orderId)\n"
            + "local response = {'SUCCESS', expiresAt}\n"
            + "for _, candidate in ipairs(allPinged) do table.insert(response, candidate) end\n"
            + "return response";

    public void acceptOrderPing(UUID driverId, UUID orderId) {
        log.info("Driver {} attempting to accept order {}", driverId, orderId);
        RedisScript<List> script = new DefaultRedisScript<>(ACCEPT_SCRIPT, List.class);
        List<String> result = redisTemplate.execute(script,
                Arrays.asList(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderId,
                        RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId,
                        RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS),
                driverId.toString(), orderId.toString(), Long.toString(System.currentTimeMillis()));
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
            log.warn("Ping for order {} and driver {} is invalid.", orderId, driverId);
            throw new IllegalArgumentException("Ping invalid.");
        }
        if (result.size() == 1 && AssignmentResult.EXPIRED.name().equals(result.get(0))) {
            log.info("Ping for order {} expired before driver {} accepted it", orderId, driverId);
            throw new IllegalStateException("Ping expired.");
        }
        if (result.size() < 2 || !AssignmentResult.SUCCESS.name().equals(result.get(0))) {
            throw new IllegalStateException("Order is no longer available.");
        }
        long originalTimeoutAt = (long) Double.parseDouble(result.get(1));
        java.util.List<String> pingedDrivers = new java.util.ArrayList<>(result.subList(2, result.size()));
        java.util.Map<String, String> dispatchDetails = resolveDispatchDetails(orderId);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                String currentLock = redisTemplate.opsForValue().get(RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId);
                if (!driverId.toString().equals(currentLock)) {
                    throw new IllegalStateException("Order lock was lost to cancellation. Aborting assignment.");
                }
                DeliveryExecutive executive = repository.findLockedById(driverId).orElseThrow(() -> new IllegalArgumentException("Driver not found: " + driverId));
                com.fooddelivery.common.event.DriverAssignedEvent event = com.fooddelivery.common.event.DriverAssignedEvent.builder()
                        .orderId(orderId.toString())
                        .driverId(driverId.toString())
                        .driverName(executive.getFullName())
                        .build();
                com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent = outboxEventHelper.createOutboxEvent(com.fooddelivery.common.constants.AggregateType.ORDER, orderId.toString(), com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED, event);
                log.info("Triggering event: DRIVER_ASSIGNED for executive: {}", executive.getId());
                outboxEventRepository.save(outboxEvent);
                recordAssignment(orderId, driverId, dispatchDetails);
                com.fooddelivery.delivery.service.state.DeliveryExecutiveState state = com.fooddelivery.delivery.service.state.DeliveryExecutiveStateFactory.getState(executive.getStatus());
                state.acceptOrder(executive);
                repository.save(executive);
                redisTemplate.opsForHash().put("drivers:status", driverId.toString(), executive.getStatus().name());
            });
        } catch (Exception e) {
            log.error("Failed to commit DRIVER_ASSIGNED transaction. Releasing Redis lock for order {}", orderId, e);
            String currentLock = redisTemplate.opsForValue().get(RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId);
            if (driverId.toString().equals(currentLock)) {
                redisTemplate.delete(RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId);
            }
            if (!pingedDrivers.isEmpty()) {
                redisTemplate.opsForSet().add(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderId, pingedDrivers.toArray(new String[0]));
                redisTemplate.expire(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderId, com.fooddelivery.delivery.service.strategy.CandidateFoundStrategy.PING_KEY_TTL);
                for (String pingedDriver : pingedDrivers) {
                    redisTemplate.opsForValue().set(RedisKeyConstants.PREFIX_DRIVER_PENDING_PING + pingedDriver,
                            orderId.toString(), com.fooddelivery.delivery.service.strategy.CandidateFoundStrategy.PING_KEY_TTL);
                }
            }
            redisTemplate.opsForZSet().add(RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS,
                    orderId.toString(), originalTimeoutAt);
            throw e;
        }

        // The database assignment is now durable. Cleanup is best-effort and must never reopen
        // the first-writer-wins Redis lock if one of these calls is temporarily unavailable.
        log.info("Driver {} accepted order {}. Emitted DRIVER_ASSIGNED event.", driverId, orderId);
        for (String pingedDriver : pingedDrivers) {
            try {
                redisTemplate.delete(RedisKeyConstants.PREFIX_DRIVER_PENDING_PING + pingedDriver);
                if (!pingedDriver.equals(driverId.toString())) {
                    logisticsDispatchService.releaseDriverLock(pingedDriver);
                }
            } catch (Exception cleanupError) {
                log.error("Failed post-commit cleanup for candidate {} on order {}", pingedDriver, orderId, cleanupError);
            }
        }
        try {
            redisTemplate.opsForValue().set(RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER + driverId,
                    orderId.toString(), java.time.Duration.ofHours(24));
            String cityId = repository.findById(driverId).map(DeliveryExecutive::getCityId).orElse(null);
            if (cityId != null) {
                redisTemplate.opsForSet().remove(RedisKeyConstants.PREFIX_DRIVERS_AVAILABLE + cityId, driverId.toString());
            }
        } catch (Exception cleanupError) {
            log.error("Failed post-commit active-order cleanup for driver {} on order {}", driverId, orderId, cleanupError);
        }
    }

    private static final String REJECT_SCRIPT = "local pendingKey = KEYS[1]\n" + "local lockKey = KEYS[2]\n" + "local driverId = ARGV[1]\n" + "if redis.call('EXISTS', lockKey) == 1 then\n" + "    redis.call('SREM', pendingKey, driverId)\n" + "    return 'ACCEPTED_ALREADY'\n" + "end\n" + "local removed = redis.call('SREM', pendingKey, driverId)\n" + "if removed == 0 then return 'NOT_FOUND' end\n" + "local remaining = redis.call('SCARD', pendingKey)\n" + "if remaining == 0 then return 'LAST_REJECT' end\n" + "return 'REJECTED'";

    public void rejectOrderPing(UUID driverId, UUID orderId) {
        log.info("Driver {} rejected order ping {}", driverId, orderId);
        RedisScript<String> script = new DefaultRedisScript<>(REJECT_SCRIPT, String.class);
        String result = redisTemplate.execute(script, Arrays.asList(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderId, RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId), driverId.toString());
        if (result == null || "NOT_FOUND".equals(result) || "ACCEPTED_ALREADY".equals(result)) {
            log.info("Ignoring stale rejection by driver {} for order {} (status={})", driverId, orderId, result);
            return;
        }
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
                    com.fooddelivery.common.event.OrderDriverRejectedEvent event = com.fooddelivery.common.event.OrderDriverRejectedEvent.builder()
                            .orderId(orderId.toString())
                            .driverId(driverId.toString())
                            .build();
                    com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent = outboxEventHelper.createOutboxEvent(com.fooddelivery.common.constants.AggregateType.ORDER, orderId.toString(), com.fooddelivery.common.constants.EventType.ORDER_DRIVER_REJECTED, event);
                    log.info("Triggering event: ORDER_DRIVER_REJECTED for aggregate: {}", orderId);
                    outboxEventRepository.save(outboxEvent);
                });
                redisTemplate.opsForZSet().remove(RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS, orderId.toString());
            } catch (Exception e) {
                log.error("Failed to save ORDER_DRIVER_REJECTED event to outbox. Reverting Redis state for order {}", orderId, e);
                redisTemplate.opsForSet().add(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderId, driverId.toString());
                redisTemplate.expire(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderId, com.fooddelivery.delivery.service.strategy.CandidateFoundStrategy.PING_KEY_TTL);
                redisTemplate.opsForValue().set(RedisKeyConstants.PREFIX_DRIVER_PENDING_PING + driverId,
                        orderId.toString(), com.fooddelivery.delivery.service.strategy.CandidateFoundStrategy.PING_KEY_TTL);
                // The Maps release above is part of what has to be undone. Restoring the ping while
                // leaving the driver advertised as free left the two halves disagreeing: a driver
                // holding a pending ping who could also be selected as a candidate for another
                // order, overwriting that ping. Best-effort -- a re-reserve that fails must not mask
                // the outbox failure that is the real error, and the rethrow below is what the
                // caller acts on.
                try {
                    logisticsDispatchService.reserveDriverLock(driverId.toString());
                } catch (Exception reserveError) {
                    log.error("Failed to re-reserve driver {} while reverting a failed decline for "
                            + "order {}; they remain advertised as available", driverId, orderId, reserveError);
                }
                throw e;
            }
        }
    }

    private static final String TIMEOUT_SCRIPT = "local pendingKey = KEYS[1]\n" + "local lockKey = KEYS[2]\n" + "if redis.call('EXISTS', lockKey) == 1 then\n" + "    redis.call('DEL', pendingKey)\n" + "    return {'ALREADY_ACCEPTED'}\n" + "end\n" + "local pendingDrivers = redis.call('SMEMBERS', pendingKey)\n" + "if #pendingDrivers == 0 then return {'EMPTY'} end\n" + "redis.call('DEL', pendingKey)\n" + "return pendingDrivers";

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
        // Who actually saw the offer. CandidateFoundStrategy records a driver here only when the
        // ping reached a live socket.
        String reachedKey = RedisKeyConstants.PREFIX_ORDER_PING_REACHED + orderIdStr;
        for (String driverIdStr : result) {
            redisTemplate.delete(RedisKeyConstants.PREFIX_DRIVER_PENDING_PING + driverIdStr);
            // A lapsed ping is only a rejection if the driver was shown the order. Counting an
            // undelivered ping against them accumulated toward the 5-strike exclusion in
            // DelayedDispatchPoller, so a driver with a dropped socket was quietly removed from
            // orders they never had the chance to take.
            boolean wasReached = Boolean.TRUE.equals(
                    redisTemplate.opsForSet().isMember(reachedKey, driverIdStr));
            if (wasReached) {
                redisTemplate.opsForHash().increment(RedisKeyConstants.PREFIX_ORDER_REJECTED_DRIVERS + orderIdStr, driverIdStr, 1);
                redisTemplate.expire(RedisKeyConstants.PREFIX_ORDER_REJECTED_DRIVERS + orderIdStr, java.time.Duration.ofHours(2));
            } else {
                log.info("PING_NEVER_DELIVERED orderId={} driverId={} -- not counting a rejection",
                        orderIdStr, driverIdStr);
            }
            try {
                log.info("Releasing driver lock for driver {} after timeout so they can receive future dispatches...", driverIdStr);
                logisticsDispatchService.releaseDriverLock(driverIdStr);
            } catch (Exception e) {
                log.error("Failed to release driver lock for driver {} on timeout, will be retried by availability poller", driverIdStr, e);
            }
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                com.fooddelivery.common.event.OrderDriverRejectedEvent event = com.fooddelivery.common.event.OrderDriverRejectedEvent.builder()
                        .orderId(orderIdStr)
                        .driverId(null)
                        .build();
                com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent = outboxEventHelper.createOutboxEvent(com.fooddelivery.common.constants.AggregateType.ORDER, orderIdStr, com.fooddelivery.common.constants.EventType.ORDER_DRIVER_REJECTED, event);
                log.info("Triggering event: ORDER_DRIVER_REJECTED for aggregate: {}", orderIdStr);
                outboxEventRepository.save(outboxEvent);
            });
            redisTemplate.opsForZSet().remove(RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS, orderIdStr);
        } catch (Exception e) {
            log.error("Failed to save ORDER_DRIVER_REJECTED event to outbox. Reverting Redis state for order {}", orderIdStr, e);
            if (result != null && !result.isEmpty()) {
                redisTemplate.opsForSet().add(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderIdStr, result.toArray(new String[0]));
                redisTemplate.expire(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderIdStr, com.fooddelivery.delivery.service.strategy.CandidateFoundStrategy.PING_KEY_TTL);
                for (String driverIdStr : result) {
                    redisTemplate.opsForValue().set(RedisKeyConstants.PREFIX_DRIVER_PENDING_PING + driverIdStr,
                            orderIdStr, com.fooddelivery.delivery.service.strategy.CandidateFoundStrategy.PING_KEY_TTL);
                }
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
        redisTemplate.opsForZSet().remove(com.fooddelivery.common.constants.RedisKeyConstants.QUEUE_DELAYED_DISPATCH, orderId.toString());
        // Force set the order driver lock
        redisTemplate.opsForValue().set(RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId, driverId.toString(), java.time.Duration.ofHours(24));
        java.util.Map<String, String> dispatchDetails = resolveDispatchDetails(orderId);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                DeliveryExecutive executive = repository.findLockedById(driverId).orElseThrow(() -> new IllegalArgumentException("Driver not found: " + driverId));
                com.fooddelivery.common.event.DriverAssignedEvent event = com.fooddelivery.common.event.DriverAssignedEvent.builder()
                        .orderId(orderId.toString())
                        .driverId(driverId.toString())
                        .driverName(executive.getFullName())
                        .build();
                com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent = outboxEventHelper.createOutboxEvent(com.fooddelivery.common.constants.AggregateType.ORDER, orderId.toString(), com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED, event);
                log.info("Triggering event: DRIVER_ASSIGNED for executive: {}", driverId);
                outboxEventRepository.save(outboxEvent);
                recordAssignment(orderId, driverId, dispatchDetails);
                if (executive.getStatus() == com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.OFFLINE) {
                    executive.setStatus(com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.ONLINE);
                }
                com.fooddelivery.delivery.service.state.DeliveryExecutiveState state = com.fooddelivery.delivery.service.state.DeliveryExecutiveStateFactory.getState(executive.getStatus());
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
            redisTemplate.opsForValue().set(RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER + driverId, orderId.toString(), java.time.Duration.ofHours(24));
            redisTemplate.opsForValue().set(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_LOCK + orderId, "CANCELLED", java.time.Duration.ofHours(24));
            String cityId = repository.findById(driverId).map(DeliveryExecutive::getCityId).orElse(null);
            if (cityId != null) {
                String key = RedisKeyConstants.PREFIX_DRIVERS_AVAILABLE + cityId;
                redisTemplate.opsForSet().remove(key, driverId.toString());
            }
        } catch (Exception e) {
            log.error("Failed post-commit active-order setup for driver {} on order {}", driverId, orderId, e);
        }
    }

    // Package-private alongside recordAssignment: together they are the payload-to-row
    // mapping, and AssignmentRecordsDispatchFactsTest drives both to pin it.
    java.util.Map<String, String> resolveDispatchDetails(UUID orderId) {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        String payload = redisTemplate.opsForValue().get(RedisKeyConstants.PREFIX_ORDER_DISPATCH_PAYLOAD + orderId);
        if (payload != null) {
            try {
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(payload);
                result.put("pickupOtp", emptyToNull(root.path("pickupOtp").asText(null)));
                result.put("deliveryOtp", emptyToNull(root.path("deliveryOtp").asText(null)));
                result.put("paymentMethod", emptyToNull(root.path("paymentMethod").asText(null)));
                return result;
            } catch (Exception e) {
                log.error("Could not read the OTPs out of the dispatch payload for order {}", orderId, e);
            }
        }
        log.warn("No dispatch payload in Redis for order {} at assignment. "
                + "Falling back to customer-service to fetch OTPs.", orderId);
        try {
            java.util.Map<String, String> details = customerServiceClient.getOrderDispatchDetails(orderId);
            if (details != null) {
                result.put("pickupOtp", emptyToNull(details.get("pickupOtp")));
                result.put("deliveryOtp", emptyToNull(details.get("deliveryOtp")));
                result.put("paymentMethod", emptyToNull(details.get("paymentMethod")));
                log.info("Successfully fetched OTP fallback from customer-service for order {}: pickupOtpPresent={}, deliveryOtpPresent={}",
                        orderId, result.get("pickupOtp") != null, result.get("deliveryOtp") != null);
                return result;
            } else {
                log.error("Customer-service returned null dispatch details for order {}. "
                        + "Assignment will be recorded without OTPs — handover will be refused.", orderId);
            }
        } catch (Exception e) {
            log.error("Failed to fetch OTPs from customer-service for order {}. "
                    + "Assignment will be recorded without OTPs — handover will be refused.", orderId, e);
        }
        return result;
    }

    /**
     * Records who holds the order, in the same transaction as the DRIVER_ASSIGNED event.
     *
     * <p>The OTPs are passed in from resolveDispatchDetails, which executes outside the
     * transaction boundary to avoid holding a DB lock during a network call.
     */
    // Package-private so the event-to-persisted-assignment mapping can be tested directly.
    void recordAssignment(UUID orderId, UUID driverId, java.util.Map<String, String> dispatchDetails) {
        String pickupOtp = dispatchDetails.get("pickupOtp");
        String deliveryOtp = dispatchDetails.get("deliveryOtp");
        com.fooddelivery.common.enums.PaymentMethod paymentMethod = null;
        String method = dispatchDetails.get("paymentMethod");
        if (method != null) {
            try {
                paymentMethod = com.fooddelivery.common.enums.PaymentMethod.valueOf(method);
            } catch (IllegalArgumentException e) {
                log.error("Unknown payment method '{}' on order {}", method, orderId);
            }
        }

        com.fooddelivery.delivery.entity.OrderAssignment assignment = assignmentRepository
                .findByOrderId(orderId)
                .orElseGet(() -> com.fooddelivery.delivery.entity.OrderAssignment.builder()
                        .orderId(orderId)
                        .build());
        assignment.setDriverId(driverId);
        assignment.setState(com.fooddelivery.delivery.entity.OrderAssignment.State.ASSIGNED);
        assignment.setAssignedAt(java.time.OffsetDateTime.now());
        assignment.setReleasedAt(null);
        if (pickupOtp != null) {
            assignment.setPickupOtp(pickupOtp);
        }
        if (deliveryOtp != null) {
            assignment.setDeliveryOtp(deliveryOtp);
        }
        if (paymentMethod != null) {
            assignment.setPaymentMethod(paymentMethod);
        }
        assignmentRepository.save(assignment);
        log.info("Recorded assignment of order {} to driver {}", orderId, driverId);
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
