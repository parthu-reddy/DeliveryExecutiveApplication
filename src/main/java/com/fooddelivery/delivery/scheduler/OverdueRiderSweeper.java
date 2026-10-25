package com.fooddelivery.delivery.scheduler;

import com.fooddelivery.common.enums.RiderReleaseReason;
import com.fooddelivery.delivery.entity.OrderAssignment;
import com.fooddelivery.delivery.repository.OrderAssignmentRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
/**
 * <strong>@replication-safe: distributed-lock</strong> -- holds a Redis lock for the sweep.
 *
 * <p>Watches riders who accepted an order and have not reached the outlet (DelayPenalties Phase 2, scenario A3, D13, D21).
 * Nothing else does: StaleDriverSweeperDaemon skips riders on a delivery, the restaurant's stuck sweep excludes
 * READY_FOR_PICKUP, and the abandoned-delivery sweep looks only at HANDED_OVER.
 *
 * <p>A rider is due at {@code max(expectedAtRestaurantAt, readyAt)}: one who has not reached the outlet while the food is
 * still cooking delays nobody (D21). At due + 5 minutes the rider is told once; at due + 10 minutes the order is taken
 * back through the same path as the rider's own Abort ({@code OrderExecutionService.releaseRider}), so redispatch is the
 * existing ORDER_DRIVER_REJECTED path, with reason OVERDUE_AUTO_REASSIGN. No expected time (maps was down, or the rider had
 * no position: D11) or food not ready yet: nothing is done for that order. Both inputs come from the assignment row; the
 * expected time is copied there from OrderService, which computed it once, the first time it is available.
 */
public class OverdueRiderSweeper {
    static final Duration WARN_AFTER = Duration.ofMinutes(5);
    static final Duration RELEASE_AFTER = Duration.ofMinutes(10);
    public static final String MESSAGE_TYPE = "OVERDUE_FOR_OUTLET";

    private final StringRedisTemplate redisTemplate;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final OrderAssignmentRepository assignmentRepository;
    private final com.fooddelivery.delivery.client.OrderServiceClient orderServiceClient;
    private final com.fooddelivery.delivery.service.OrderExecutionService orderExecutionService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final Clock clock;

    @Scheduled(fixedDelay = 60000)
    public void sweepOverdueRiders() {
        com.fooddelivery.common.lock.RedisLock redisLock = new com.fooddelivery.common.lock.RedisLock(redisTemplate);
        String lockToken = UUID.randomUUID().toString();
        if (!redisLock.tryAcquire(com.fooddelivery.common.constants.RedisKeyConstants.LOCK_SWEEP_OVERDUE_RIDERS, lockToken,
                Duration.ofSeconds(50))) {
            return;
        }
        try {
            com.fooddelivery.common.lock.LockedWorkTimer.timed(meterRegistry, "overdueRiderSweeper", Duration.ofSeconds(50), () -> {
                try {
                    sweep();
                } catch (Exception e) {
                    log.error("Error during OverdueRiderSweeper execution", e);
                }
            });
        } finally {
            redisLock.release(com.fooddelivery.common.constants.RedisKeyConstants.LOCK_SWEEP_OVERDUE_RIDERS, lockToken);
        }
    }

    /** One pass. Each order is handled on its own, so one failure does not stop the rest. */
    void sweep() {
        Instant now = clock.instant();
        // Due is never before readyAt, so nothing whose food was ready less than WARN_AFTER ago can be late yet.
        for (OrderAssignment assignment : assignmentRepository.findOverdueCandidates(now.minus(WARN_AFTER))) {
            try {
                examine(assignment, now);
            } catch (Exception e) {
                log.error("OVERDUE_RIDER_CHECK_FAILED orderId={} driverId={}", assignment.getOrderId(), assignment.getDriverId(), e);
            }
        }
    }

    void examine(OrderAssignment assignment, Instant now) {
        if (assignment.getAtRestaurantAt() != null || assignment.getReadyAt() == null
                || assignment.getState() != OrderAssignment.State.ASSIGNED) {
            return;
        }
        Instant expected = expectedAtRestaurant(assignment);
        if (expected == null) {
            log.info("OVERDUE_RIDER_NOT_MEASURABLE orderId={} driverId={} reason=NO_EXPECTED_ARRIVAL", assignment.getOrderId(),
                    assignment.getDriverId());
            return;
        }
        Instant due = expected.isAfter(assignment.getReadyAt()) ? expected : assignment.getReadyAt();
        if (!now.isBefore(due.plus(RELEASE_AFTER))) {
            log.warn("OVERDUE_RIDER_RELEASED orderId={} driverId={} due={} now={}", assignment.getOrderId(),
                    assignment.getDriverId(), due, now);
            orderExecutionService.releaseRider(assignment.getDriverId(), assignment.getOrderId(), RiderReleaseReason.OVERDUE_AUTO_REASSIGN);
            meterRegistry.counter("delivery.overdue_rider.released").increment();
        } else if (!now.isBefore(due.plus(WARN_AFTER)) && assignment.getOverdueNotifiedAt() == null) {
            assignment.setOverdueNotifiedAt(now);
            assignmentRepository.save(assignment);
            notifyRider(assignment.getDriverId(), assignment.getOrderId(), due.plus(RELEASE_AFTER));
            meterRegistry.counter("delivery.overdue_rider.warned").increment();
        }
    }

    /**
     * T3e for the assignment's own rider, from the row, else from OrderService, which wrote it once off its Kafka listener
     * thread after the assignment. Copied onto the row the first time it is there. An answer about another rider, or none,
     * is no expected time.
     */
    private Instant expectedAtRestaurant(OrderAssignment assignment) {
        if (assignment.getExpectedAtRestaurantAt() != null) {
            return assignment.getExpectedAtRestaurantAt();
        }
        Map<String, String> details;
        try {
            details = orderServiceClient.getOrderDispatchDetails(assignment.getOrderId());
        } catch (Exception unavailable) {
            log.warn("OVERDUE_RIDER_EXPECTED_UNAVAILABLE orderId={} errorType={}", assignment.getOrderId(),
                    unavailable.getClass().getSimpleName());
            return null;
        }
        if (details == null || details.get("expectedAtRestaurantAt") == null
                || !assignment.getDriverId().toString().equals(details.get("deliveryExecutiveId"))) {
            return null;
        }
        Instant expected = Instant.parse(details.get("expectedAtRestaurantAt"));
        assignment.setExpectedAtRestaurantAt(expected);
        assignmentRepository.save(assignment);
        return expected;
    }

    /** On {@code ws:driver:{id}}, the channel dispatch pings and duty changes already use. Best effort. */
    private void notifyRider(UUID driverId, UUID orderId, Instant releaseAt) {
        try {
            redisTemplate.convertAndSend("ws:driver:" + driverId, objectMapper.writeValueAsString(Map.of(
                    "type", MESSAGE_TYPE, "orderId", orderId.toString(), "releaseAt", releaseAt.toString())));
            log.warn("OVERDUE_RIDER_WARNED orderId={} driverId={} releaseAt={}", orderId, driverId, releaseAt);
        } catch (Exception e) {
            log.warn("OVERDUE_RIDER_WARN_FAILED orderId={} driverId={}", orderId, driverId, e);
        }
    }
}
