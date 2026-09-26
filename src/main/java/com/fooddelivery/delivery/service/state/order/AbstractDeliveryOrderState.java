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
            saveOutboxEvent(driverId, orderId, status, pickupOtp, deliveryOtp);
            updateExecutiveState(driverId, goOfflineAfter);
            return null;
        });
        postProcess(driverId, orderId, goOfflineAfter);
    }

    protected void validate(OrderAssignment assignment, String pickupOtp, String deliveryOtp) {
        // Default no-op. Override in subclasses if validation is needed.
    }

    /**
     * Compares a submitted OTP against the assignment.
     *
     * <p>Constant time, and an absent expected value denies rather than passes -- an OTP check that
     * cannot find what it is comparing against has not succeeded.
     */
    protected void requireOtp(String expected, String submitted, String what) {
        if (expected == null || expected.isBlank()) {
            log.error("No {} OTP recorded on the assignment; refusing the handover.", what);
            throw new IllegalArgumentException("Invalid " + what + " OTP.");
        }
        byte[] a = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = (submitted == null ? "" : submitted).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (!java.security.MessageDigest.isEqual(a, b)) {
            log.error("{} OTP mismatch.", what);
            throw new IllegalArgumentException("Invalid " + what + " OTP.");
        }
    }

    protected void saveOutboxEvent(UUID driverId, UUID orderId, DeliveryStatus status, String pickupOtp, String deliveryOtp) {
        Object event = null;
        switch (getEventType()) {
            case ORDER_AT_RESTAURANT:
                event = com.fooddelivery.common.event.DriverAtRestaurantEvent.builder()
                        .orderId(orderId.toString())
                        .driverId(driverId.toString())
                        .build();
                break;
            case ORDER_STATUS_UPDATED:
                event = com.fooddelivery.common.event.OutForDeliveryEvent.builder()
                        .orderId(orderId.toString())
                        .status(status.name())
                        .pickupOtp(pickupOtp)
                        .build();
                break;
            case ORDER_DELIVERED:
                event = com.fooddelivery.common.event.DeliveredEvent.builder()
                        .orderId(orderId.toString())
                        .status(status.name())
                        .deliveryOtp(deliveryOtp)
                        .build();
                break;
            case DELIVERY_FAILED:
                event = com.fooddelivery.common.event.DeliveryFailedEvent.builder()
                        .orderId(orderId.toString())
                        .reason(status.name())
                        .build();
                break;
            default:
                throw new IllegalStateException("Unknown event type: " + getEventType());
        }
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
        assignmentRepository.findByOrderId(orderId).ifPresent(assignment -> {
            assignment.setState(OrderAssignment.State.RELEASED);
            assignment.setReleasedAt(java.time.Instant.now());
            assignmentRepository.save(assignment);
        });
    }

    protected abstract com.fooddelivery.common.constants.EventType getEventType();
}
