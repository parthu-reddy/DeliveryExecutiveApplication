package com.fooddelivery.delivery.service.state.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fooddelivery.common.constants.AppConstants;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.common.enums.OutboxStatus;
import com.fooddelivery.common.outbox.entity.OutboxEventEntity;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.entity.OrderAssignment;
import com.fooddelivery.delivery.repository.OrderAssignmentRepository;
import org.springframework.security.access.AccessDeniedException;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.LogisticsDispatchService;
import com.fooddelivery.delivery.service.state.DeliveryExecutiveStateFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.UUID;
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public abstract class AbstractDeliveryOrderState implements DeliveryOrderStateStrategy {
protected final StringRedisTemplate redisTemplate;
    protected final ObjectMapper objectMapper;
    protected final TransactionTemplate transactionTemplate;
    protected final OutboxEventRepository outboxEventRepository;
    protected final IDeliveryExecutiveRepository repository;
    protected final LogisticsDispatchService logisticsDispatchService;
    protected final OrderAssignmentRepository assignmentRepository;
    /** The rider's step is stamped with this clock when the delivery service accepts it (DelayPenalties Phase 1). */
    protected final java.time.Clock clock;


    /**
     * Fails closed on the assignment.
     *
     * <p>This used to read the Redis key {@code order:driver:lock:<id>} and skip the update only
     * when it named a <em>different</em> driver:
     *
     * <pre>if (currentAssignee != null && !driverId.equals(currentAssignee)) return;</pre>
     *
     * <p>The key carries a 24-hour TTL. Once it was gone -- eviction, a restart without
     * persistence, an order older than a day -- the condition was false for everyone and any driver
     * could post any status for any order. An absent assignment now denies.
     */
    @Override
    public void handleStatusUpdate(UUID driverId, UUID orderId, DeliveryStatus status, String pickupOtp, String deliveryOtp, Boolean goOfflineAfter) {
        log.info("DELIVERY_STATE_TRANSITION_STARTED driverId={} orderId={} targetStatus={}", driverId, orderId, status);
        OrderAssignment assignment = assignmentRepository.findByOrderId(orderId).orElse(null);
        if (assignment == null || !assignment.authorises(driverId)) {
            log.warn("Refusing status update: order {} is not assigned to driver {} ({})", orderId, driverId,
                    assignment == null ? "no assignment" : assignment.getState() + " to " + assignment.getDriverId());
            throw new AccessDeniedException("This order is not assigned to you.");
        }
        validate(assignment, pickupOtp, deliveryOtp);
        transactionTemplate.execute(txStatus -> {
            // Same transaction as the event: /orders/active overlays this, so the rider never sees
            // a step behind one the delivery service has already accepted.
            assignment.recordDeliveryStatus(status);
            assignmentRepository.save(assignment);
            saveOutboxEvent(driverId, orderId, status, pickupOtp, deliveryOtp);
            updateExecutiveState(driverId, goOfflineAfter);
            return null;
        });
        postProcess(driverId, orderId, goOfflineAfter);
    }

    protected void validate(OrderAssignment assignment, String pickupOtp, String deliveryOtp) {
        // Default no-op. Override in subclasses if validation is needed.
    }

    /** Wrong OTPs allowed per order and kind within {@link #OTP_LOCKOUT} before every attempt is refused. */
    static final int MAX_WRONG_OTP_ATTEMPTS = 5;
    /** Counted from the first wrong attempt; the lock lifts by itself when it ends. */
    static final java.time.Duration OTP_LOCKOUT = java.time.Duration.ofMinutes(5);
    static final String OTP_FAILURES_PREFIX = "delivery:otp-failures:";

    /**
     * Compares a submitted OTP against the assignment.
     *
     * <p>Constant time, and an absent expected value denies rather than passes -- an OTP check that
     * cannot find what it is comparing against has not succeeded.
     *
     * <p>Wrong attempts are capped (F8, 2026-10-09). Before, the only brake was the gateway's per-IP
     * limit (20/s), so the assigned rider could guess the 6-digit delivery OTP and mark an order
     * delivered without handing it over. After {@link #MAX_WRONG_OTP_ATTEMPTS} wrong attempts every
     * attempt, the right code included, is refused with 429 until {@link #OTP_LOCKOUT} has passed:
     * about one guess a minute, instead of thousands. Only the assigned rider reaches this check,
     * so nobody else can lock a rider out.
     */
    protected void requireOtp(UUID orderId, String expected, String submitted, String what) {
        String failuresKey = OTP_FAILURES_PREFIX + orderId + ":" + what;
        String failures = redisTemplate.opsForValue().get(failuresKey);
        if (failures != null && Long.parseLong(failures) >= MAX_WRONG_OTP_ATTEMPTS) {
            log.warn("Refusing {} OTP for order {}: {} wrong attempts", what, orderId, failures);
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "Too many incorrect " + what + " OTP attempts. Try again in a few minutes, or contact support.");
        }
        if (expected == null || expected.isBlank()) {
            log.error("No {} OTP recorded on the assignment; refusing the handover.", what);
            throw new IllegalArgumentException("Invalid " + what + " OTP.");
        }
        byte[] a = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = (submitted == null ? "" : submitted).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (!java.security.MessageDigest.isEqual(a, b)) {
            log.error("{} OTP mismatch.", what);
            // The window starts with the first failure; INCR keeps the TTL that SET NX gave the key.
            redisTemplate.opsForValue().setIfAbsent(failuresKey, "0", OTP_LOCKOUT);
            redisTemplate.opsForValue().increment(failuresKey);
            throw new IllegalArgumentException("Invalid " + what + " OTP.");
        }
        redisTemplate.delete(failuresKey);
    }

    /**
     * The event this step publishes. Public so the producer contract (BaseMessagingClass) publishes exactly what this
     * service writes to the outbox, not a hand-built copy.
     *
     * <p>Every milestone carries {@code occurredAt} from {@link #clock}: OrderService stores it as the time the step
     * happened, whatever Kafka does to the message afterwards. OUT_FOR_DELIVERY is an {@code OrderStatusUpdatedEvent}, the
     * class every consumer binds ORDER_STATUS_UPDATED to; it no longer carries the pickup OTP, which no consumer read.
     */
    public Object outboxPayload(UUID driverId, UUID orderId, DeliveryStatus status, String pickupOtp, String deliveryOtp) {
        java.time.Instant occurredAt = clock.instant();
        switch (getEventType()) {
            case ORDER_AT_RESTAURANT:
                return com.fooddelivery.common.event.DriverAtRestaurantEvent.builder()
                        .orderId(orderId.toString())
                        .driverId(driverId.toString())
                        .occurredAt(occurredAt)
                        .build();
            case ORDER_STATUS_UPDATED:
                return com.fooddelivery.common.event.OrderStatusUpdatedEvent.builder()
                        .orderId(orderId.toString())
                        .status(status.name())
                        .occurredAt(occurredAt)
                        .build();
            case ORDER_DELIVERED:
                return com.fooddelivery.common.event.DeliveredEvent.builder()
                        .orderId(orderId.toString())
                        .status(status.name())
                        .deliveryOtp(deliveryOtp)
                        .occurredAt(occurredAt)
                        .build();
            case DELIVERY_FAILED:
                return com.fooddelivery.common.event.DeliveryFailedEvent.builder()
                        .orderId(orderId.toString())
                        .reason(status.name())
                        .build();
            default:
                throw new IllegalStateException("Unknown event type: " + getEventType());
        }
    }

    protected void saveOutboxEvent(UUID driverId, UUID orderId, DeliveryStatus status, String pickupOtp, String deliveryOtp) {
        Object event = outboxPayload(driverId, orderId, status, pickupOtp, deliveryOtp);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize status update payload", e);
        }
        OutboxEventEntity outboxEvent = OutboxEventEntity.builder().id(UUID.randomUUID()).aggregateType(com.fooddelivery.common.constants.AggregateType.ORDER).aggregateId(orderId.toString()).eventType(getEventType()).payload(payload).createdAt(Instant.now()).status(OutboxStatus.UNPROCESSED).build();
        log.info("DELIVERY_EVENT_ENQUEUED orderId={} driverId={} eventType={} eventId={}",
                orderId, driverId, getEventType().name(), outboxEvent.getId());
        outboxEventRepository.save(outboxEvent);
    }

    protected void updateExecutiveState(UUID driverId, Boolean goOfflineAfter) {
        // Default no-op. Override for DELIVERED / FAILED.
    }

    protected void postProcess(UUID driverId, UUID orderId, Boolean goOfflineAfter) {
        // Default no-op. Override for DELIVERED / FAILED.
    }

    /**
     * Ends the assignment's authority. The row is kept as a record of who held the order; it simply
     * stops satisfying {@link OrderAssignment#authorises}.
     */
    protected void releaseAssignment(UUID orderId) {
        assignmentRepository.findByOrderId(orderId)
                .filter(assignment -> assignment.release(Instant.now()))
                .ifPresent(assignmentRepository::save);
    }

    public abstract com.fooddelivery.common.constants.EventType getEventType();
}
