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

    private static final String ACCEPT_SCRIPT = "local pendingKey = KEYS[1]\n" + "local lockKey = KEYS[2]\n" + "local driverId = ARGV[1]\n" + "if redis.call(\'EXISTS\', lockKey) == 1 then\n" + "    local lockVal = redis.call(\'GET\', lockKey)\n" + "    if lockVal == \'CANCELLED\' then return {\'CANCELLED\'} end\n" + "    return {\'ALREADY_ACCEPTED\'}\n" + "end\n" + "if redis.call(\'SISMEMBER\', pendingKey, driverId) == 0 then return {\'INVALID\'} end\n" + "redis.call(\'SET\', lockKey, driverId, \'EX\', 86400)\n" + "local allPinged = redis.call(\'SMEMBERS\', pendingKey)\n" + "redis.call(\'DEL\', pendingKey)\n" + "if #allPinged == 0 then return {\'SUCCESS_EMPTY\'} end\n" + "return allPinged";

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
                com.fooddelivery.common.event.DriverAssignedEvent event = com.fooddelivery.common.event.DriverAssignedEvent.builder()
                        .orderId(orderId.toString())
                        .driverId(driverId.toString())
                        .driverName(executive.getFullName())
                        .build();
                com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent = outboxEventHelper.createOutboxEvent(com.fooddelivery.common.constants.AggregateType.ORDER, orderId.toString(), com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED, event);
                log.info("Triggering event: DRIVER_ASSIGNED for executive: {}", executive.getId());
                outboxEventRepository.save(outboxEvent);
                recordAssignment(orderId, driverId);
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
                String cityId = repository.findById(driverId).map(DeliveryExecutive::getCityId).orElse(null);
                if (cityId != null) {
                    String key = "drivers:available:" + cityId;
                    redisTemplate.opsForSet().remove(key, driverId.toString());
                }
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

    private static final String REJECT_SCRIPT = "local pendingKey = KEYS[1]\n" + "local lockKey = KEYS[2]\n" + "local driverId = ARGV[1]\n" + "if redis.call(\'EXISTS\', lockKey) == 1 then\n" + "    redis.call(\'SREM\', pendingKey, driverId)\n" + "    return \'ACCEPTED_ALREADY\'\n" + "end\n" + "local removed = redis.call(\'SREM\', pendingKey, driverId)\n" + "if removed == 0 then return \'NOT_FOUND\' end\n" + "local remaining = redis.call(\'SCARD\', pendingKey)\n" + "if remaining == 0 then return \'LAST_REJECT\' end\n" + "return \'REJECTED\'";

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
                redisTemplate.expire(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + orderId, java.time.Duration.ofMinutes(5));
                throw e;
            }
        }
    }

    private static final String TIMEOUT_SCRIPT = "local pendingKey = KEYS[1]\n" + "local lockKey = KEYS[2]\n" + "if redis.call(\'EXISTS\', lockKey) == 1 then\n" + "    redis.call(\'DEL\', pendingKey)\n" + "    return {\'ALREADY_ACCEPTED\'}\n" + "end\n" + "local pendingDrivers = redis.call(\'SMEMBERS\', pendingKey)\n" + "if #pendingDrivers == 0 then return {\'EMPTY\'} end\n" + "redis.call(\'DEL\', pendingKey)\n" + "return pendingDrivers";

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
                com.fooddelivery.common.event.OrderDriverRejectedEvent event = com.fooddelivery.common.event.OrderDriverRejectedEvent.builder()
                        .orderId(orderIdStr)
                        .driverId(result.get(0))
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
                com.fooddelivery.common.event.DriverAssignedEvent event = com.fooddelivery.common.event.DriverAssignedEvent.builder()
                        .orderId(orderId.toString())
                        .driverId(driverId.toString())
                        .driverName(executive.getFullName())
                        .build();
                com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent = outboxEventHelper.createOutboxEvent(com.fooddelivery.common.constants.AggregateType.ORDER, orderId.toString(), com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED, event);
                log.info("Triggering event: DRIVER_ASSIGNED for executive: {}", driverId);
                outboxEventRepository.save(outboxEvent);
                recordAssignment(orderId, driverId);
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
            String cityId = repository.findById(driverId).map(DeliveryExecutive::getCityId).orElse(null);
            if (cityId != null) {
                String key = "drivers:available:" + cityId;
                redisTemplate.opsForSet().remove(key, driverId.toString());
            }
        } catch (Exception e) {
            log.error("Failed to remove driver {} from Redis pool", driverId, e);
        }
    }

    /**
     * Records who holds the order, in the same transaction as the DRIVER_ASSIGNED event.
     *
     * <p>The OTPs are lifted out of the cached ORDER_ACCEPTED payload here and persisted, so that
     * from this point on neither the assignment nor the handover proof depends on a Redis key with
     * a 24-hour TTL. If the payload is already gone at assignment time the row is still written --
     * an assignment with no OTP denies the handover, which is the safe direction, whereas the old
     * behaviour of reading a missing key at handover time denied it permanently and silently.
     */
    // Package-private, not private: the wiring from the ORDER_ACCEPTED payload into this row is
    // what makes a COD handover enforceable downstream, and nothing pinned it until a break-test
    // removed the paymentMethod assignment and every test still passed.
    void recordAssignment(UUID orderId, UUID driverId) {
        String pickupOtp = null;
        String deliveryOtp = null;
        com.fooddelivery.common.enums.PaymentMethod paymentMethod = null;
        String payload = redisTemplate.opsForValue()
                .get(RedisKeyConstants.PREFIX_ORDER_DISPATCH_PAYLOAD + orderId);
        if (payload != null) {
            try {
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(payload);
                pickupOtp = emptyToNull(root.path("pickupOtp").asText(null));
                deliveryOtp = emptyToNull(root.path("deliveryOtp").asText(null));
                String method = emptyToNull(root.path("paymentMethod").asText(null));
                if (method != null) {
                    try {
                        paymentMethod = com.fooddelivery.common.enums.PaymentMethod.valueOf(method);
                    } catch (IllegalArgumentException e) {
                        log.error("Unknown payment method '{}' on order {}", method, orderId);
                    }
                }
            } catch (Exception e) {
                log.error("Could not read the OTPs out of the dispatch payload for order {}", orderId, e);
            }
        } else {
            log.warn("No dispatch payload in Redis for order {} at assignment. "
                    + "Falling back to customer-service to fetch OTPs.", orderId);
            try {
                java.util.Map<String, String> details = customerServiceClient.getOrderDispatchDetails(orderId);
                if (details != null) {
                    pickupOtp = emptyToNull(details.get("pickupOtp"));
                    deliveryOtp = emptyToNull(details.get("deliveryOtp"));
                    String method = emptyToNull(details.get("paymentMethod"));
                    if (method != null) {
                        try {
                            paymentMethod = com.fooddelivery.common.enums.PaymentMethod.valueOf(method);
                        } catch (IllegalArgumentException e) {
                            log.error("Unknown payment method '{}' from customer-service for order {}", method, orderId);
                        }
                    }
                    log.info("Successfully fetched OTPs from customer-service for order {}: pickupOtp={}, deliveryOtp={}",
                            orderId, pickupOtp != null ? "[set]" : "[null]", deliveryOtp != null ? "[set]" : "[null]");
                } else {
                    log.error("Customer-service returned null dispatch details for order {}. "
                            + "Assignment will be recorded without OTPs — handover will be refused.", orderId);
                }
            } catch (Exception e) {
                log.error("Failed to fetch OTPs from customer-service for order {}. "
                        + "Assignment will be recorded without OTPs — handover will be refused.", orderId, e);
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
