package com.fooddelivery.delivery.service.strategy;

import com.fooddelivery.common.event.OrderScopedEvent;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.entity.OrderAssignment;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.repository.OrderAssignmentRepository;
import com.fooddelivery.delivery.service.LogisticsDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Terminal order events end the order's assignment. Until 2026-10-17 this strategy cleaned Redis and reset the rider
 * but left {@code order_assignments} ASSIGNED, so an order failed by the abandoned-delivery sweeper, or cancelled
 * after assignment, kept authorising its rider, and manual assignment refused that rider with DRIVER_ALREADY_ASSIGNED.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TerminalStateStrategyTest {

    private static final List<String> NOT_TERMINAL = List.of("DRIVER_ASSIGNED", "DISPATCH_FAILED");
    private static final Instant ASSIGNED_AT = Instant.parse("2026-10-17T00:00:00Z");

    @Mock(answer = Answers.RETURNS_DEEP_STUBS) StringRedisTemplate redisTemplate;
    @Mock IDeliveryExecutiveRepository executiveRepository;
    @Mock LogisticsDispatchService logisticsDispatchService;
    @Mock TransactionTemplate transactionTemplate;
    @Mock OrderAssignmentRepository assignmentRepository;
    @InjectMocks TerminalStateStrategy strategy;

    private final UUID orderId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();

    @BeforeEach
    void runTransactionsInline() {
        doAnswer(inv -> {
            Consumer<org.springframework.transaction.TransactionStatus> work = inv.getArgument(0);
            work.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        doAnswer(inv -> {
            TransactionCallback<?> work = inv.getArgument(0);
            return work.doInTransaction(null);
        }).when(transactionTemplate).execute(any());
        // Redis knows nothing about the order unless a test says otherwise.
        when(redisTemplate.opsForValue().get(any())).thenReturn(null);
        when(redisTemplate.opsForSet().members(any())).thenReturn(null);
    }

    @Test
    void testGetEventTypes() {
        assertThat(strategy.getEventTypes()).containsExactlyInAnyOrder(
            "DRIVER_ASSIGNED", "DISPATCH_FAILED", "ORDER_CANCELLED", "DELIVERY_FAILED",
            "ORDER_CANCELLED_BY_RESTAURANT", "ORDER_CANCELLED_BY_CUSTOMER", "ORDER_CANCELLED_BY_ADMIN",
            "ORDER_REJECTED", "ORDER_DELAY_REJECTED", "MANUAL_INTERVENTION_REQUIRED"
        );
    }

    /** Every type the strategy declares, minus the two that are not the end of an order. */
    static Stream<String> terminalTypes() {
        return new TerminalStateStrategy(null, null, null, null, null).getEventTypes().stream()
                .filter(type -> !NOT_TERMINAL.contains(type));
    }

    static Stream<String> nonTerminalTypes() {
        return NOT_TERMINAL.stream();
    }

    private OrderAssignment assigned() {
        return OrderAssignment.builder().orderId(orderId).driverId(driverId).state(OrderAssignment.State.ASSIGNED)
                .assignedAt(ASSIGNED_AT).version(0).build();
    }

    private OrderScopedEvent event() {
        OrderScopedEvent event = mock(OrderScopedEvent.class);
        when(event.orderUuid()).thenReturn(orderId);
        return event;
    }

    @Test
    void deliveryFailedReleasesTheAssignment() throws Exception {
        OrderAssignment assignment = assigned();
        when(assignmentRepository.findLockedByOrderId(orderId)).thenReturn(Optional.of(assignment));

        strategy.handle(event(), "DELIVERY_FAILED");

        assertThat(assignment.getState()).isEqualTo(OrderAssignment.State.RELEASED);
        assertThat(assignment.getReleasedAt()).isAfter(ASSIGNED_AT);
        assertThat(assignment.authorises(driverId)).isFalse();
        verify(assignmentRepository).save(assignment);
    }

    @ParameterizedTest
    @MethodSource("terminalTypes")
    void everyTerminalTypeReleasesTheAssignment(String type) throws Exception {
        OrderAssignment assignment = assigned();
        when(assignmentRepository.findLockedByOrderId(orderId)).thenReturn(Optional.of(assignment));

        strategy.handle(event(), type);

        assertThat(assignment.getState()).isEqualTo(OrderAssignment.State.RELEASED);
        assertThat(assignment.getReleasedAt()).isNotNull();
        verify(assignmentRepository).save(assignment);
    }

    @ParameterizedTest
    @MethodSource("nonTerminalTypes")
    void assignmentAndDispatchFailureLeaveTheAssignmentAlone(String type) throws Exception {
        strategy.handle(event(), type);

        verify(assignmentRepository, never()).findLockedByOrderId(any());
        verify(assignmentRepository, never()).findByOrderId(any());
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void aSecondTerminalEventIsANoOp() throws Exception {
        OrderAssignment released = assigned();
        Instant firstRelease = Instant.parse("2026-10-17T01:00:00Z");
        released.release(firstRelease);
        when(assignmentRepository.findLockedByOrderId(orderId)).thenReturn(Optional.of(released));

        strategy.handle(event(), "DELIVERY_FAILED");

        assertThat(released.getReleasedAt()).isEqualTo(firstRelease);
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void anOrderWithoutAnAssignmentIsFine() throws Exception {
        when(assignmentRepository.findLockedByOrderId(orderId)).thenReturn(Optional.empty());

        strategy.handle(event(), "ORDER_CANCELLED_BY_ADMIN");

        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void withEveryRedisKeyGoneTheRiderIsStillFreedFromTheAssignment() throws Exception {
        // The driver lock, stranded key and ping set have all expired: the durable row is the only record left.
        OrderAssignment assignment = assigned();
        when(assignmentRepository.findLockedByOrderId(orderId)).thenReturn(Optional.of(assignment));
        DeliveryExecutive rider = new DeliveryExecutive();
        rider.setId(driverId);
        rider.setStatus(DeliveryExecutiveStatus.ON_DELIVERY);
        when(executiveRepository.findLockedById(driverId)).thenReturn(Optional.of(rider));

        strategy.handle(event(), "DELIVERY_FAILED");

        assertThat(assignment.getState()).isEqualTo(OrderAssignment.State.RELEASED);
        assertThat(rider.getStatus()).isEqualTo(DeliveryExecutiveStatus.ONLINE);
        verify(logisticsDispatchService).releaseDriverLock(driverId.toString());
    }

    @Test
    void theRidersActiveOrderKeyIsClearedOnlyWhileItNamesThisOrder() throws Exception {
        when(assignmentRepository.findLockedByOrderId(orderId)).thenReturn(Optional.of(assigned()));

        strategy.handle(event(), "ORDER_CANCELLED_BY_ADMIN");

        org.mockito.ArgumentCaptor<org.springframework.data.redis.core.script.RedisScript<Long>> script =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.redis.core.script.RedisScript.class);
        verify(redisTemplate).execute(script.capture(),
                eq(List.of(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER + driverId)),
                eq(orderId.toString()));
        // compare-and-delete: a newer order on the same rider keeps its key
        assertThat(script.getValue().getScriptAsString()).contains("redis.call('GET', KEYS[1]) == ARGV[1]");
    }

    @Test
    void aRiderWhoAlreadyGaveTheOrderUpIsNotResetFromIt() throws Exception {
        // Released before the cancellation (the rider aborted): that row names a rider who may hold another order now.
        OrderAssignment released = assigned();
        released.release(Instant.parse("2026-10-17T01:00:00Z"));
        when(assignmentRepository.findLockedByOrderId(orderId)).thenReturn(Optional.of(released));

        strategy.handle(event(), "ORDER_CANCELLED_BY_CUSTOMER");

        verify(executiveRepository, never()).findLockedById(any());
        verify(logisticsDispatchService, never()).releaseDriverLock(any());
    }

    @Test
    void onlyThisOrdersAssignmentIsTouched() throws Exception {
        // The same rider may already hold another order; the strategy never looks anything up by driver.
        OrderAssignment assignment = assigned();
        when(assignmentRepository.findLockedByOrderId(orderId)).thenReturn(Optional.of(assignment));

        strategy.handle(event(), "DELIVERY_FAILED");

        verify(assignmentRepository).findLockedByOrderId(orderId);
        verify(assignmentRepository).save(assignment);
        verifyNoMoreInteractions(assignmentRepository);
    }
}
