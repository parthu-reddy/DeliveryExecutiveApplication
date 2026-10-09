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
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F8 (2026-10-09 review): the pickup and delivery OTPs had no attempt limit. The assigned rider could guess the
 * customer's 6-digit delivery OTP at the gateway's 20/s and mark the order delivered without handing it over.
 * Five wrong attempts now lock that OTP for five minutes; Redis is an in-memory map here, TTLs recorded.
 */
class OtpAttemptLimitTest {

    private final UUID driverId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final Map<String, String> store = new HashMap<>();
    private final Map<String, Duration> ttls = new HashMap<>();
    private OutboxEventRepository outbox;
    private StringRedisTemplate redis;
    private IDeliveryExecutiveRepository executives;
    private OrderAssignmentRepository assignments;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        outbox = mock(OutboxEventRepository.class);
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        lenient().when(redis.opsForHash()).thenReturn(mock(org.springframework.data.redis.core.HashOperations.class));
        lenient().when(redis.opsForZSet()).thenReturn(mock(org.springframework.data.redis.core.ZSetOperations.class));
        when(values.get(anyString())).thenAnswer(i -> store.get(i.<String>getArgument(0)));
        lenient().when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(i -> {
            String key = i.getArgument(0);
            if (store.containsKey(key)) return false;
            store.put(key, i.getArgument(1));
            ttls.put(key, i.getArgument(2));
            return true;
        });
        lenient().when(values.increment(anyString())).thenAnswer(i ->
                Long.valueOf(store.merge(i.<String>getArgument(0), "1", (a, b) -> String.valueOf(Long.parseLong(a) + 1))));
        lenient().when(redis.delete(anyString())).thenAnswer(i -> store.remove(i.<String>getArgument(0)) != null);
        store.put("order:restaurantStatus:" + orderId, "READY_FOR_PICKUP");

        executives = mock(IDeliveryExecutiveRepository.class);
        com.fooddelivery.delivery.entity.DeliveryExecutive executive = new com.fooddelivery.delivery.entity.DeliveryExecutive();
        executive.setId(driverId);
        executive.setStatus(com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.ON_DELIVERY);
        lenient().when(executives.findLockedById(driverId)).thenReturn(Optional.of(executive));
        assignments = mock(OrderAssignmentRepository.class);
        when(assignments.findByOrderId(orderId)).thenReturn(Optional.of(OrderAssignment.builder()
                .orderId(orderId).driverId(driverId).state(OrderAssignment.State.ASSIGNED).assignedAt(Instant.now())
                .pickupOtp("123456").deliveryOtp("654321").build()));
    }

    private TransactionTemplate tx() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        lenient().when(tx.execute(any())).thenAnswer(i -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = i.getArgument(0);
            return cb.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });
        return tx;
    }

    private void deliver(String otp) {
        new DeliveredStateStrategy(redis, new ObjectMapper(), tx(), outbox, executives,
                mock(LogisticsDispatchService.class), assignments)
                .handleStatusUpdate(driverId, orderId, DeliveryStatus.DELIVERED, null, otp, false);
    }

    private void pickUp(String otp) {
        new OutForDeliveryStateStrategy(redis, new ObjectMapper(), tx(), outbox, executives,
                mock(LogisticsDispatchService.class), assignments)
                .handleStatusUpdate(driverId, orderId, DeliveryStatus.OUT_FOR_DELIVERY, otp, null, false);
    }

    private void wrongDeliveries(int n) {
        for (int i = 0; i < n; i++) {
            assertThrows(IllegalArgumentException.class, () -> deliver("000000"));
        }
    }

    @Test
    void afterFiveWrongAttemptsEvenTheRightCodeIsRefused() {
        wrongDeliveries(AbstractDeliveryOrderState.MAX_WRONG_OTP_ATTEMPTS);

        ResponseStatusException locked = assertThrows(ResponseStatusException.class, () -> deliver("654321"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, locked.getStatusCode());
        verifyNoInteractions(outbox);
    }

    @Test
    void theRightCodeBeforeTheCapStillDelivers() {
        wrongDeliveries(AbstractDeliveryOrderState.MAX_WRONG_OTP_ATTEMPTS - 1);

        assertDoesNotThrow(() -> deliver("654321"));
        verify(outbox).save(any());
        assertFalse(store.containsKey(AbstractDeliveryOrderState.OTP_FAILURES_PREFIX + orderId + ":delivery"),
                "a successful handover clears the count");
    }

    @Test
    void theLockLiftsByItselfAfterTheWindow() {
        wrongDeliveries(1);

        assertEquals(AbstractDeliveryOrderState.OTP_LOCKOUT,
                ttls.get(AbstractDeliveryOrderState.OTP_FAILURES_PREFIX + orderId + ":delivery"),
                "the counter must expire, or one bad evening locks the order for good");
    }

    @Test
    void pickupAndDeliveryAreCountedSeparately() {
        wrongDeliveries(AbstractDeliveryOrderState.MAX_WRONG_OTP_ATTEMPTS);

        assertDoesNotThrow(() -> pickUp("123456"), "a locked delivery OTP does not block the pickup");
    }

    @Test
    void wrongPickupsAreCappedToo() {
        for (int i = 0; i < AbstractDeliveryOrderState.MAX_WRONG_OTP_ATTEMPTS; i++) {
            assertThrows(IllegalArgumentException.class, () -> pickUp("999999"));
        }

        assertThrows(ResponseStatusException.class, () -> pickUp("123456"));
    }
}
