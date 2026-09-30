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

    @org.springframework.beans.factory.annotation.Value("${app.rider.biometric-verification.enabled:true}")
    private boolean biometricVerificationEnabled = true;

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

    /**
     * Kept only so a stale internal caller fails closed rather than recreating the unaudited
     * force-assignment path. New calls must carry the CustomerApplication operation correlation.
     */
    @Deprecated(forRemoval = true)
    public void forceAssignOrder(UUID orderId, UUID driverId) {
        throw new UnsupportedOperationException(
                "Manual assignment requires an authenticated operation from CustomerApplication.");
    }

    /**
     * Applies an audited manual assignment after CustomerApplication has authenticated the
     * administrator and persisted the current operation on the order.
     *
     * <p>Every destructive Redis change occurs after the database/outbox transaction commits.
     * A rejected rider therefore leaves the existing dispatch pings and delayed dispatch path
     * intact.
     */
    public ManualForceAssignmentResult forceAssignOrder(ManualForceAssignment command) {
        final ManualDispatchContext dispatch;
        try {
            dispatch = loadManualDispatchContext(command);
        } catch (ManualAssignmentSupersededException superseded) {
            log.info("MANUAL_ASSIGNMENT_IGNORED orderId={} operationId={} reason={}",
                    command.orderId(), command.operationId(), superseded.getMessage());
            return ManualForceAssignmentResult.superseded();
        }
        ForceAssignmentOutcome outcome = transactionTemplate.execute(status -> {
            try {
                return commitManualForceAssignment(command, dispatch);
            } catch (ManualAssignmentRejectedException rejection) {
                // Every deterministic rejection occurs before any assignment mutation. Recording
                // the result in this transaction makes the command terminal and visible to the
                // operator instead of letting it retry until DLT.
                return recordManualAssignmentRejection(command, rejection);
            }
        });
        if (outcome == null) {
            throw new IllegalStateException("Manual assignment transaction completed without a durable outcome.");
        }
        if (outcome.replayed()) {
            return ManualForceAssignmentResult.replayResult();
        }
        if (outcome.rejected()) {
            return ManualForceAssignmentResult.rejected(outcome.rejectionCode());
        }
        projectManualAssignmentAfterCommit(command, outcome);
        return ManualForceAssignmentResult.appliedResult();
    }

    private ForceAssignmentOutcome commitManualForceAssignment(
            ManualForceAssignment command,
            ManualDispatchContext dispatch) {
        String outboxIdempotencyKey = "delivery-force-assignment:" + command.operationId();
        String failureIdempotencyKey = "delivery-manual-assignment-failure:" + command.operationId();
        java.util.Optional<com.fooddelivery.common.outbox.entity.OutboxEventEntity> existingOutbox =
                java.util.Optional.ofNullable(outboxEventRepository.findByIdempotencyKey(outboxIdempotencyKey))
                        .orElse(java.util.Optional.empty());
        java.util.Optional<com.fooddelivery.common.outbox.entity.OutboxEventEntity> existingFailure =
                java.util.Optional.ofNullable(outboxEventRepository.findByIdempotencyKey(failureIdempotencyKey))
                        .orElse(java.util.Optional.empty());
        if (existingOutbox.isPresent() && existingFailure.isPresent()) {
            throw new IllegalStateException(
                    "Manual assignment has both a success and failure result for the same operation.");
        }
        if (existingOutbox.isPresent()) {
            validateManualReplay(existingOutbox.get(), command);
            return ForceAssignmentOutcome.idempotentReplay();
        }
        if (existingFailure.isPresent()) {
            return ForceAssignmentOutcome.idempotentRejection(
                    validateManualFailureReplay(existingFailure.get(), command));
        }

        com.fooddelivery.delivery.entity.OrderAssignment currentAssignment = assignmentRepository
                .findLockedByOrderId(command.orderId())
                .orElse(null);
        UUID previousDriverId = liveDriver(currentAssignment);
        boolean sameDriver = command.driverId().equals(previousDriverId);
        if (currentAssignment != null
                && currentAssignment.getState() == com.fooddelivery.delivery.entity.OrderAssignment.State.ASSIGNED
                && !sameDriver
                && currentAssignment.getDeliveryStatus() != null
                && currentAssignment.getDeliveryStatus() != com.fooddelivery.common.enums.DeliveryStatus.ASSIGNED) {
            throw new ManualAssignmentRejectedException("ORDER_NOT_REASSIGNABLE",
                    "Cannot reassign an order after the assigned rider has started delivery.");
        }
        if (sameDriver) {
            throw new ManualAssignmentRejectedException("ORDER_ALREADY_ASSIGNED",
                    "The requested driver already holds this order assignment.");
        }

        java.util.Map<UUID, DeliveryExecutive> lockedDrivers = lockDrivers(command.driverId(), previousDriverId);
        DeliveryExecutive target = lockedDrivers.get(command.driverId());
        boolean oldDriverReleased = false;
        String oldDriverCity = null;

        RiderReadiness.requireManualAssignmentReady(
                target, dispatch.dispatchCityId(), biometricVerificationEnabled, java.time.Instant.now(),
                hasFreshLocation(target.getId(), dispatch.dispatchCityId()));
        if (assignmentRepository.existsByDriverIdAndStateAndOrderIdNot(
                command.driverId(), com.fooddelivery.delivery.entity.OrderAssignment.State.ASSIGNED,
                command.orderId())) {
            throw new ManualAssignmentRejectedException("DRIVER_ALREADY_ASSIGNED",
                    "Driver already has another active assignment.");
        }

        if (previousDriverId != null) {
            DeliveryExecutive previous = lockedDrivers.get(previousDriverId);
            if (previous == null) {
                throw new ManualAssignmentRejectedException("CURRENT_ASSIGNMENT_INCONSISTENT",
                        "Current assigned driver could not be locked for reassignment.");
            }
            boolean hasAnotherAssignment = assignmentRepository.existsByDriverIdAndStateAndOrderIdNot(
                    previousDriverId, com.fooddelivery.delivery.entity.OrderAssignment.State.ASSIGNED,
                    command.orderId());
            if (!hasAnotherAssignment
                    && previous.getStatus() == com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.ON_DELIVERY) {
                previous.setStatus(com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.ONLINE);
                repository.save(previous);
                oldDriverReleased = true;
                oldDriverCity = previous.getCityId();
            }
        }

        recordAssignment(command.orderId(), command.driverId(), dispatch.details(), currentAssignment);
        com.fooddelivery.delivery.service.state.DeliveryExecutiveStateFactory
                .getState(target.getStatus()).acceptOrder(target);
        repository.save(target);

        com.fooddelivery.common.event.DriverAssignedEvent event =
                com.fooddelivery.common.event.DriverAssignedEvent.builder()
                        .orderId(command.orderId().toString())
                        .driverId(command.driverId().toString())
                        .driverName(target.getFullName())
                        .operationId(command.operationId())
                        .actorId(command.actorId().toString())
                        .reason(command.reason())
                        .assignmentSource("MANUAL_INTERVENTION")
                        .fromDriverId(previousDriverId == null ? null : previousDriverId.toString())
                        .build();
        com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent =
                outboxEventHelper.createOutboxEvent(
                        com.fooddelivery.common.constants.AggregateType.ORDER,
                        command.orderId().toString(),
                        com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED,
                        event);
        outboxEvent.setIdempotencyKey(outboxIdempotencyKey);
        outboxEventRepository.save(outboxEvent);

        log.info("MANUAL_ASSIGNMENT_COMMITTED orderId={} driverId={} actorId={} operationId={} previousDriverId={}",
                command.orderId(), command.driverId(), command.actorId(), command.operationId(), previousDriverId);
        return new ForceAssignmentOutcome(false, null, previousDriverId, oldDriverReleased, oldDriverCity,
                dispatch.dispatchCityId());
    }

    private ForceAssignmentOutcome recordManualAssignmentRejection(
            ManualForceAssignment command,
            ManualAssignmentRejectedException rejection) {
        String failureIdempotencyKey = "delivery-manual-assignment-failure:" + command.operationId();
        com.fooddelivery.common.event.ManualAssignmentFailedEvent event =
                com.fooddelivery.common.event.ManualAssignmentFailedEvent.builder()
                        .orderId(command.orderId().toString())
                        .operationId(command.operationId())
                        .actorId(command.actorId().toString())
                        .driverId(command.driverId().toString())
                        .reasonCode(rejection.reasonCode())
                        .timestamp(System.currentTimeMillis())
                        .build();
        com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent =
                outboxEventHelper.createOutboxEvent(
                        com.fooddelivery.common.constants.AggregateType.ORDER,
                        command.orderId().toString(),
                        com.fooddelivery.common.constants.EventType.MANUAL_ASSIGNMENT_FAILED,
                        event);
        outboxEvent.setIdempotencyKey(failureIdempotencyKey);
        outboxEventRepository.save(outboxEvent);
        log.warn("MANUAL_ASSIGNMENT_REJECTED orderId={} driverId={} actorId={} operationId={} reasonCode={}",
                command.orderId(), command.driverId(), command.actorId(), command.operationId(),
                rejection.reasonCode());
        return ForceAssignmentOutcome.rejected(rejection.reasonCode());
    }

    private ManualDispatchContext loadManualDispatchContext(ManualForceAssignment command) {
        java.util.Map<String, String> details;
        try {
            details = customerServiceClient.getOrderDispatchDetails(command.orderId());
        } catch (Exception unavailable) {
            throw new IllegalStateException(
                    "Unable to validate the current order before manual assignment.", unavailable);
        }
        if (details == null) {
            throw new IllegalStateException("Customer service returned no order context for manual assignment.");
        }
        String deliveryStatus = details.get("deliveryStatus");
        String operationId = details.get("manualInterventionOperationId");
        String requestedDriverId = details.get("manualInterventionRequestedDriverId");
        String dispatchCityId = emptyToNull(details.get("dispatchCityId"));
        if (!com.fooddelivery.common.enums.DeliveryStatus.MANUAL_INTERVENTION_REQUIRED.name().equals(deliveryStatus)) {
            throw new ManualAssignmentSupersededException(
                    "Order is no longer awaiting manual dispatch intervention.");
        }
        if (!command.operationId().equals(operationId)
                || !command.driverId().toString().equals(requestedDriverId)) {
            throw new ManualAssignmentSupersededException(
                    "Manual assignment was superseded by a newer order intervention.");
        }
        if (dispatchCityId == null || !dispatchCityId.equals(command.dispatchCityId())) {
            throw new ManualAssignmentSupersededException(
                    "Manual assignment dispatch city does not match the current order.");
        }
        java.util.Map<String, String> assignmentDetails = new java.util.HashMap<>();
        assignmentDetails.put("pickupOtp", emptyToNull(details.get("pickupOtp")));
        assignmentDetails.put("deliveryOtp", emptyToNull(details.get("deliveryOtp")));
        assignmentDetails.put("paymentMethod", emptyToNull(details.get("paymentMethod")));
        return new ManualDispatchContext(assignmentDetails, dispatchCityId);
    }

    private java.util.Map<UUID, DeliveryExecutive> lockDrivers(UUID targetDriverId, UUID previousDriverId) {
        java.util.List<UUID> ids = new java.util.ArrayList<>();
        ids.add(targetDriverId);
        if (previousDriverId != null && !previousDriverId.equals(targetDriverId)) {
            ids.add(previousDriverId);
        }
        ids.sort(java.util.Comparator.naturalOrder());
        java.util.Map<UUID, DeliveryExecutive> locked = new java.util.HashMap<>();
        for (UUID id : ids) {
            DeliveryExecutive executive = repository.findLockedById(id).orElse(null);
            if (executive == null) {
                String reasonCode = id.equals(targetDriverId)
                        ? "DRIVER_NOT_FOUND"
                        : "CURRENT_ASSIGNMENT_INCONSISTENT";
                throw new ManualAssignmentRejectedException(reasonCode, "Driver not found: " + id);
            }
            locked.put(id, executive);
        }
        return locked;
    }

    private UUID liveDriver(com.fooddelivery.delivery.entity.OrderAssignment assignment) {
        if (assignment == null
                || assignment.getState() != com.fooddelivery.delivery.entity.OrderAssignment.State.ASSIGNED) {
            return null;
        }
        return assignment.getDriverId();
    }

    private boolean hasFreshLocation(UUID driverId, String cityId) {
        try {
            Double lastPing = redisTemplate.opsForZSet().score(RiderLiveness.LAST_PING_KEY, driverId.toString());
            if (lastPing == null
                    || lastPing < System.currentTimeMillis() - RiderLiveness.MAX_SIGNAL_AGE_MS) {
                return false;
            }
            java.util.List<org.springframework.data.geo.Point> positions = redisTemplate.opsForGeo()
                    .position("drivers:geo:" + cityId, driverId.toString());
            return positions != null && !positions.isEmpty() && positions.get(0) != null;
        } catch (Exception redisUnavailable) {
            log.warn("MANUAL_ASSIGNMENT_READINESS_UNAVAILABLE driverId={} cityId={}", driverId, cityId,
                    redisUnavailable);
            return false;
        }
    }

    private void validateManualReplay(
            com.fooddelivery.common.outbox.entity.OutboxEventEntity event,
            ManualForceAssignment command) {
        if (event.getEventType() != com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED) {
            throw new IllegalStateException("Manual assignment operation conflicts with a different outbox event.");
        }
        try {
            com.fooddelivery.common.event.DriverAssignedEvent payload = objectMapper.readValue(
                    event.getPayload(), com.fooddelivery.common.event.DriverAssignedEvent.class);
            if (!command.orderId().toString().equals(payload.getOrderId())
                    || !command.driverId().toString().equals(payload.getDriverId())
                    || !command.operationId().equals(payload.getOperationId())
                    || !command.actorId().toString().equals(payload.getActorId())
                    || !command.reason().equals(payload.getReason())) {
                throw new IllegalStateException(
                        "Manual assignment operation conflicts with a different request payload.");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalidPayload) {
            throw new IllegalStateException("Manual assignment audit payload cannot be read.", invalidPayload);
        }
    }

    private String validateManualFailureReplay(
            com.fooddelivery.common.outbox.entity.OutboxEventEntity event,
            ManualForceAssignment command) {
        if (event.getEventType() != com.fooddelivery.common.constants.EventType.MANUAL_ASSIGNMENT_FAILED) {
            throw new IllegalStateException("Manual assignment failure operation conflicts with a different outbox event.");
        }
        try {
            com.fooddelivery.common.event.ManualAssignmentFailedEvent payload = objectMapper.readValue(
                    event.getPayload(), com.fooddelivery.common.event.ManualAssignmentFailedEvent.class);
            if (!command.orderId().toString().equals(payload.getOrderId())
                    || !command.driverId().toString().equals(payload.getDriverId())
                    || !command.operationId().equals(payload.getOperationId())
                    || !command.actorId().toString().equals(payload.getActorId())
                    || payload.getReasonCode() == null
                    || payload.getReasonCode().isBlank()) {
                throw new IllegalStateException(
                        "Manual assignment failure operation conflicts with a different request payload.");
            }
            return payload.getReasonCode();
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalidPayload) {
            throw new IllegalStateException("Manual assignment failure audit payload cannot be read.", invalidPayload);
        }
    }

    /**
     * The Kafka listener already owns a transaction. {@link TransactionTemplate} therefore joins
     * it, which means returning from {@code execute} does not necessarily mean the assignment is
     * durable yet. Redis is only a projection of the durable assignment, so defer projection
     * until that enclosing transaction commits. Direct callers without an enclosing transaction
     * have already committed by the time this method runs and can project immediately.
     */
    private void projectManualAssignmentAfterCommit(
            ManualForceAssignment command,
            ForceAssignmentOutcome outcome) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            projectCommittedManualAssignment(command, outcome);
                        }
                    });
            return;
        }
        projectCommittedManualAssignment(command, outcome);
    }

    private void projectCommittedManualAssignment(
            ManualForceAssignment command,
            ForceAssignmentOutcome outcome) {
        try {
            redisTemplate.opsForValue().set(RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + command.orderId(),
                    command.driverId().toString(), java.time.Duration.ofHours(24));
            redisTemplate.opsForValue().set(RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER + command.driverId(),
                    command.orderId().toString(), java.time.Duration.ofHours(24));
            redisTemplate.opsForValue().set(RedisKeyConstants.PREFIX_ORDER_DISPATCH_LOCK + command.orderId(),
                    "CANCELLED", java.time.Duration.ofHours(24));
            redisTemplate.opsForHash().put("drivers:status", command.driverId().toString(),
                    com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.ON_DELIVERY.name());
            redisTemplate.opsForSet().remove(
                    RedisKeyConstants.PREFIX_DRIVERS_AVAILABLE + outcome.dispatchCityId(),
                    command.driverId().toString());

            java.util.Set<String> pendingDrivers = redisTemplate.opsForSet()
                    .members(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + command.orderId());
            redisTemplate.delete(RedisKeyConstants.PREFIX_ORDER_PING_PENDING + command.orderId());
            redisTemplate.opsForZSet().remove(RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS,
                    command.orderId().toString());
            redisTemplate.opsForZSet().remove(RedisKeyConstants.QUEUE_DELAYED_DISPATCH,
                    command.orderId().toString());
            if (pendingDrivers != null) {
                for (String pendingDriverId : pendingDrivers) {
                    redisTemplate.delete(RedisKeyConstants.PREFIX_DRIVER_PENDING_PING + pendingDriverId);
                    if (!command.driverId().toString().equals(pendingDriverId)) {
                        releasePendingDriverAfterCommittedForce(pendingDriverId, command.orderId());
                    }
                }
            }
            if (outcome.oldDriverId() != null) {
                clearActiveOrderIfMatches(outcome.oldDriverId(), command.orderId());
                if (outcome.oldDriverReleased()
                        && outcome.oldDriverCity() != null
                        && hasFreshLocation(outcome.oldDriverId(), outcome.oldDriverCity())) {
                    logisticsDispatchService.releaseDriverLock(outcome.oldDriverId().toString());
                }
            }
        } catch (Exception projectionFailure) {
            // The durable assignment and outbox event have already committed. A later reconciliation
            // can repair Redis; throwing here would make Kafka retry a completed command.
            log.error("MANUAL_ASSIGNMENT_PROJECTION_FAILED orderId={} operationId={}",
                    command.orderId(), command.operationId(), projectionFailure);
        }
    }

    private void releasePendingDriverAfterCommittedForce(String driverId, UUID orderId) {
        try {
            logisticsDispatchService.releaseDriverLock(driverId);
        } catch (Exception releaseFailure) {
            log.error("Failed to release pending driver {} after committed manual assignment for order {}",
                    driverId, orderId, releaseFailure);
        }
    }

    private static final String DELETE_ACTIVE_ORDER_IF_MATCHES =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0";

    private void clearActiveOrderIfMatches(UUID driverId, UUID orderId) {
        try {
            redisTemplate.execute(new DefaultRedisScript<>(DELETE_ACTIVE_ORDER_IF_MATCHES, Long.class),
                    java.util.List.of(RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER + driverId),
                    orderId.toString());
        } catch (Exception redisFailure) {
            log.error("Failed to clear previous active-order projection for driver {} and order {}",
                    driverId, orderId, redisFailure);
        }
    }

    private record ManualDispatchContext(java.util.Map<String, String> details, String dispatchCityId) {
    }

    private record ForceAssignmentOutcome(
            boolean replayed,
            String rejectionCode,
            UUID oldDriverId,
            boolean oldDriverReleased,
            String oldDriverCity,
            String dispatchCityId) {
        static ForceAssignmentOutcome idempotentReplay() {
            return new ForceAssignmentOutcome(true, null, null, false, null, null);
        }

        static ForceAssignmentOutcome rejected(String rejectionCode) {
            return new ForceAssignmentOutcome(false, rejectionCode, null, false, null, null);
        }

        static ForceAssignmentOutcome idempotentRejection(String rejectionCode) {
            return rejected(rejectionCode);
        }

        boolean rejected() {
            return rejectionCode != null;
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
        recordAssignment(orderId, driverId, dispatchDetails, assignmentRepository
                .findByOrderId(orderId)
                .orElse(null));
    }

    /**
     * Uses the caller's row lock for a manual reassignment. The ordinary first-winner path keeps
     * using the public overload above because its Redis Lua lock already serialises candidates.
     */
    private void recordAssignment(
            UUID orderId,
            UUID driverId,
            java.util.Map<String, String> dispatchDetails,
            com.fooddelivery.delivery.entity.OrderAssignment existingAssignment) {
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

        com.fooddelivery.delivery.entity.OrderAssignment assignment = existingAssignment != null
                ? existingAssignment
                : com.fooddelivery.delivery.entity.OrderAssignment.builder()
                        .orderId(orderId)
                        .build();
        boolean riderChanged = assignment.getDriverId() != null && !driverId.equals(assignment.getDriverId());
        assignment.setDriverId(driverId);
        assignment.setState(com.fooddelivery.delivery.entity.OrderAssignment.State.ASSIGNED);
        assignment.setAssignedAt(java.time.Instant.now());
        assignment.setReleasedAt(null);
        if (riderChanged) {
            // A replacement rider has not confirmed any handover state from the former rider.
            assignment.setDeliveryStatus(null);
        }
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
