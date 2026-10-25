package com.fooddelivery.delivery.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.enums.RiderReleaseReason;
import com.fooddelivery.delivery.client.OrderServiceClient;
import com.fooddelivery.delivery.entity.OrderAssignment;
import com.fooddelivery.delivery.repository.OrderAssignmentRepository;
import com.fooddelivery.delivery.service.OrderExecutionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A rider who has not reached the outlet is warned once at due + 5 min and released through the Abort path at due + 10
 * min, where due = max(expected arrival, food ready) (DelayPenalties Phase 2, D13, D21). No expected time, or food not ready,
 * means nothing happens.
 */
class OverdueRiderSweeperTest {

    private static final Instant NOW = Instant.parse("2026-10-25T09:30:00Z");
    private final UUID orderId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private OrderAssignmentRepository assignments;
    private OrderServiceClient orders;
    private OrderExecutionService execution;
    private StringRedisTemplate redis;
    private OrderAssignment assignment;

    @BeforeEach
    void setUp() {
        assignments = mock(OrderAssignmentRepository.class);
        orders = mock(OrderServiceClient.class);
        execution = mock(OrderExecutionService.class);
        redis = mock(StringRedisTemplate.class);
        assignment = OrderAssignment.builder().orderId(orderId).driverId(driverId).state(OrderAssignment.State.ASSIGNED)
                .assignedAt(NOW.minusSeconds(3600)).build();
    }

    private OverdueRiderSweeper sweeper(Instant now) {
        return new OverdueRiderSweeper(redis, new SimpleMeterRegistry(), assignments, orders, execution, new ObjectMapper(),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    /** OrderService's answer: the current rider and their expected arrival. */
    private void expected(Instant at, UUID rider) {
        when(orders.getOrderDispatchDetails(orderId)).thenReturn(Map.of("expectedAtRestaurantAt", at.toString(),
                "deliveryExecutiveId", rider.toString()));
    }

    @Test
    void warnsOnceFiveMinutesAfterDue() {
        assignment.setReadyAt(NOW.minusSeconds(20 * 60));
        expected(NOW.minusSeconds(6 * 60), driverId);

        sweeper(NOW).examine(assignment, NOW);

        assertEquals(NOW, assignment.getOverdueNotifiedAt());
        verify(redis).convertAndSend(eq("ws:driver:" + driverId), contains(OverdueRiderSweeper.MESSAGE_TYPE));
        verifyNoInteractions(execution);

        sweeper(NOW.plusSeconds(60)).examine(assignment, NOW.plusSeconds(60));
        verify(redis, times(1)).convertAndSend(anyString(), anyString());
    }

    @Test
    void beforeFiveMinutesNothingHappens() {
        assignment.setReadyAt(NOW.minusSeconds(20 * 60));
        expected(NOW.minusSeconds(4 * 60), driverId);

        sweeper(NOW).examine(assignment, NOW);

        assertNull(assignment.getOverdueNotifiedAt());
        verifyNoInteractions(redis, execution);
    }

    @Test
    void releasesTenMinutesAfterDueThroughTheAbortPath() {
        assignment.setReadyAt(NOW.minusSeconds(30 * 60));
        expected(NOW.minusSeconds(10 * 60), driverId);

        sweeper(NOW).examine(assignment, NOW);

        verify(execution).releaseRider(driverId, orderId, RiderReleaseReason.OVERDUE_AUTO_REASSIGN);
    }

    @Test
    void foodNotReadyUntilAfterTheExpectedArrivalMeasuresFromReady() {
        // D21: expected 15 minutes ago, but the food was ready only 4 minutes ago. Due is the Ready tap.
        assignment.setReadyAt(NOW.minusSeconds(4 * 60));
        expected(NOW.minusSeconds(15 * 60), driverId);

        sweeper(NOW).examine(assignment, NOW);

        verifyNoInteractions(redis, execution);
    }

    @Test
    void notReadyYetDoesNothingAndIsNotACandidate() {
        expected(NOW.minusSeconds(30 * 60), driverId);
        when(assignments.findOverdueCandidates(NOW.minus(OverdueRiderSweeper.WARN_AFTER))).thenReturn(List.of());

        sweeper(NOW).sweep();
        sweeper(NOW).examine(assignment, NOW);

        verify(assignments).findOverdueCandidates(NOW.minus(OverdueRiderSweeper.WARN_AFTER));
        verifyNoInteractions(redis, execution, orders);
    }

    @Test
    void noExpectedTimeIsNotMeasurableAndDoesNothing() {
        assignment.setReadyAt(NOW.minusSeconds(60 * 60));
        when(orders.getOrderDispatchDetails(orderId)).thenReturn(Map.of("deliveryExecutiveId", driverId.toString()));

        sweeper(NOW).examine(assignment, NOW);

        verifyNoInteractions(redis, execution);
    }

    @Test
    void orderServiceDownIsNotMeasurableAndDoesNothing() {
        assignment.setReadyAt(NOW.minusSeconds(60 * 60));
        when(orders.getOrderDispatchDetails(orderId)).thenThrow(new RuntimeException("down"));

        assertDoesNotThrow(() -> sweeper(NOW).examine(assignment, NOW));
        verifyNoInteractions(redis, execution);
    }

    @Test
    void anExpectedTimeForAnotherRiderIsIgnored() {
        assignment.setReadyAt(NOW.minusSeconds(60 * 60));
        expected(NOW.minusSeconds(60 * 60), UUID.randomUUID());

        sweeper(NOW).examine(assignment, NOW);

        assertNull(assignment.getExpectedAtRestaurantAt());
        verifyNoInteractions(redis, execution);
    }

    @Test
    void theExpectedTimeIsCopiedOntoTheAssignmentOnce() {
        assignment.setReadyAt(NOW.minusSeconds(20 * 60));
        expected(NOW.minusSeconds(60), driverId);

        sweeper(NOW).examine(assignment, NOW);
        sweeper(NOW).examine(assignment, NOW);

        assertEquals(NOW.minusSeconds(60), assignment.getExpectedAtRestaurantAt());
        verify(orders, times(1)).getOrderDispatchDetails(orderId);
    }

    @Test
    void aRiderWhoReachedTheOutletIsLeftAlone() {
        assignment.setReadyAt(NOW.minusSeconds(60 * 60));
        assignment.recordArrival(com.fooddelivery.common.enums.DeliveryStatus.AT_RESTAURANT, NOW.minusSeconds(50 * 60), false);
        expected(NOW.minusSeconds(60 * 60), driverId);

        sweeper(NOW).examine(assignment, NOW);

        verifyNoInteractions(redis, execution, orders);
    }
}
