package com.fooddelivery.delivery.service.state.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.delivery.entity.OrderAssignment;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.repository.OrderAssignmentRepository;
import com.fooddelivery.delivery.service.LogisticsDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A driver may only act on an order assigned to them, and an absent assignment denies.
 *
 * <p>The guard this replaces read the Redis key {@code order:driver:lock:<id>} and skipped only
 * when it named a <em>different</em> driver — so once that key expired (24-hour TTL) the condition
 * was false for everyone and any driver could post any status for any order. These tests pin the
 * missing-record case in particular, because that is the one that was open.
 */
class DeliveryAssignmentAuthorizationTest {

    private OrderAssignmentRepository assignments;
    private OutboxEventRepository outbox;
    private AtRestaurantStateStrategy strategy;

    private final UUID assignedDriver = UUID.randomUUID();
    private final UUID otherDriver = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        assignments = mock(OrderAssignmentRepository.class);
        outbox = mock(OutboxEventRepository.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(i -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = i.getArgument(0);
            return cb.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });
        strategy = new AtRestaurantStateStrategy(mock(StringRedisTemplate.class), new ObjectMapper(),
                tx, outbox, mock(IDeliveryExecutiveRepository.class),
                mock(LogisticsDispatchService.class), assignments);
    }

    private OrderAssignment assignment(UUID driverId, OrderAssignment.State state) {
        return OrderAssignment.builder()
                .orderId(orderId).driverId(driverId).state(state)
                .assignedAt(Instant.now())
                .pickupOtp("111111").deliveryOtp("222222")
                .build();
    }

    private void update(UUID driverId) {
        strategy.handleStatusUpdate(driverId, orderId, DeliveryStatus.AT_RESTAURANT, null, null, false);
    }

    @Test
    void aDriverWithNoAssignmentIsDenied() {
        when(assignments.findByOrderId(orderId)).thenReturn(Optional.empty());

        assertThrows(AccessDeniedException.class, () -> update(otherDriver));
        verifyNoInteractions(outbox);
    }

    @Test
    void aDriverWhoIsNotTheAssigneeIsDenied() {
        when(assignments.findByOrderId(orderId))
                .thenReturn(Optional.of(assignment(assignedDriver, OrderAssignment.State.ASSIGNED)));

        assertThrows(AccessDeniedException.class, () -> update(otherDriver));
        verifyNoInteractions(outbox);
    }

    @Test
    void aReleasedAssignmentNoLongerAuthorises() {
        when(assignments.findByOrderId(orderId))
                .thenReturn(Optional.of(assignment(assignedDriver, OrderAssignment.State.RELEASED)));

        assertThrows(AccessDeniedException.class, () -> update(assignedDriver));
        verifyNoInteractions(outbox);
    }

    @Test
    void theAssignedDriverIsAllowedThrough() {
        when(assignments.findByOrderId(orderId))
                .thenReturn(Optional.of(assignment(assignedDriver, OrderAssignment.State.ASSIGNED)));

        assertDoesNotThrow(() -> update(assignedDriver));
        verify(outbox).save(any());
    }

    /** The missing-record case denies rather than allowing, in every direction. */
    @Test
    void anAbsentAssignmentDeniesEvenTheDriverWhoOnceHeldIt() {
        when(assignments.findByOrderId(orderId)).thenReturn(Optional.empty());

        assertThrows(AccessDeniedException.class, () -> update(assignedDriver));
    }
}
