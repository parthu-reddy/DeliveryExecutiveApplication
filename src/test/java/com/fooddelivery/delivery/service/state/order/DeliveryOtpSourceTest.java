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
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The OTPs come from the assignment row, not from the cached dispatch payload.
 *
 * <p>Both validators used to read {@code order:dispatch:payload:<id>}, a Redis key with a 24-hour
 * TTL. When it was gone the rider got "Invalid Delivery OTP. Order payload not found." and the
 * order could not be completed by anyone, by any route — a lost Redis key permanently stranded a
 * paid order with the food already delivered.
 */
class DeliveryOtpSourceTest {

    private OrderAssignmentRepository assignments;
    private OutboxEventRepository outbox;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;

    private final UUID driverId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        assignments = mock(OrderAssignmentRepository.class);
        outbox = mock(OutboxEventRepository.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        lenient().when(redis.opsForValue()).thenReturn(values);
        // The dispatch payload key is GONE. That used to be fatal.
        lenient().when(values.get(anyString())).thenReturn(null);

        when(assignments.findByOrderId(orderId)).thenReturn(Optional.of(OrderAssignment.builder()
                .orderId(orderId).driverId(driverId)
                .state(OrderAssignment.State.ASSIGNED)
                .assignedAt(Instant.now())
                .pickupOtp("123456").deliveryOtp("654321")
                .build()));
    }

    private TransactionTemplate tx() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        lenient().when(tx.execute(any())).thenAnswer(i -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = i.getArgument(0);
            return cb.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });
        return tx;
    }

    private DeliveredStateStrategy delivered() {
        return new DeliveredStateStrategy(redis, new ObjectMapper(), tx(), outbox,
                mock(IDeliveryExecutiveRepository.class), mock(LogisticsDispatchService.class), assignments);
    }

    private OutForDeliveryStateStrategy outForDelivery() {
        return new OutForDeliveryStateStrategy(redis, new ObjectMapper(), tx(), outbox,
                mock(IDeliveryExecutiveRepository.class), mock(LogisticsDispatchService.class), assignments);
    }

    @Test
    void deliveryStillCompletesWithTheDispatchPayloadGone() {
        DeliveredStateStrategy strategy = delivered();

        // updateExecutiveState needs a driver row this test does not stand up, so the call fails
        // *after* validate(). Classify on the message rather than the type: "Driver not found" is
        // also an IllegalArgumentException, and catching the type alone reported a passing OTP
        // check as a failing one.
        try {
            strategy.handleStatusUpdate(driverId, orderId, DeliveryStatus.DELIVERED, null, "654321", false);
        } catch (RuntimeException e) {
            assertFalse(String.valueOf(e.getMessage()).contains("OTP"),
                    "the correct delivery OTP was refused because the Redis payload was missing: "
                            + e.getMessage());
        }
        // The outbox write happens inside the transaction, after validate() and before the driver
        // lookup -- so seeing it is direct evidence the OTP check passed.
        verify(outbox).save(any());
    }

    @Test
    void aWrongDeliveryOtpIsStillRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> delivered().handleStatusUpdate(driverId, orderId, DeliveryStatus.DELIVERED,
                        null, "000000", false));
        assertTrue(e.getMessage().contains("delivery"), e.getMessage());
        verifyNoInteractions(outbox);
    }

    @Test
    void anAbsentOtpOnTheAssignmentDeniesRatherThanPasses() {
        when(assignments.findByOrderId(orderId)).thenReturn(Optional.of(OrderAssignment.builder()
                .orderId(orderId).driverId(driverId)
                .state(OrderAssignment.State.ASSIGNED)
                .assignedAt(Instant.now())
                .build()));

        assertThrows(IllegalArgumentException.class,
                () -> delivered().handleStatusUpdate(driverId, orderId, DeliveryStatus.DELIVERED,
                        null, "654321", false));
        verifyNoInteractions(outbox);
    }

    @Test
    void pickupStillRequiresTheRestaurantToBeReady() {
        when(values.get(contains("restaurant-status"))).thenReturn(null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> outForDelivery().handleStatusUpdate(driverId, orderId,
                        DeliveryStatus.OUT_FOR_DELIVERY, "123456", null, false));
        assertTrue(e.getMessage().contains("ready"), e.getMessage());
    }

    @Test
    void pickupSucceedsWithTheCorrectOtpOnceReady() {
        when(values.get(contains(orderId.toString()))).thenReturn("READY_FOR_PICKUP");

        assertDoesNotThrow(() -> outForDelivery().handleStatusUpdate(driverId, orderId,
                DeliveryStatus.OUT_FOR_DELIVERY, "123456", null, false));
        verify(outbox).save(any());
    }

    @Test
    void aWrongPickupOtpIsRefusedEvenWhenReady() {
        when(values.get(contains(orderId.toString()))).thenReturn("READY_FOR_PICKUP");

        assertThrows(IllegalArgumentException.class, () -> outForDelivery().handleStatusUpdate(
                driverId, orderId, DeliveryStatus.OUT_FOR_DELIVERY, "999999", null, false));
        verifyNoInteractions(outbox);
    }
}
