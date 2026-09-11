package com.fooddelivery.delivery.service.state.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.common.enums.PaymentMethod;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.delivery.entity.OrderAssignment;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.repository.OrderAssignmentRepository;
import com.fooddelivery.delivery.service.LogisticsDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A cash delivery cannot be completed without saying how much cash.
 *
 * <p>The rider has always posted a {@code cashCollectedAmount}; nothing required it and nothing
 * downstream read it, so the customer service booked the order total as collected regardless. Now
 * that the amount is what gets booked, an absent one has to be refused here rather than guessed
 * there.
 */
class CodDeliveryRequiresDeclaredCashTest {

    private OrderAssignmentRepository assignments;
    private OutboxEventRepository outbox;
    private DeliveredStateStrategy strategy;

    private final UUID driverId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        assignments = mock(OrderAssignmentRepository.class);
        outbox = mock(OutboxEventRepository.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        lenient().when(tx.execute(any())).thenAnswer(i -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = i.getArgument(0);
            return cb.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });
        strategy = new DeliveredStateStrategy(mock(StringRedisTemplate.class), new ObjectMapper(), tx,
                outbox, mock(IDeliveryExecutiveRepository.class), mock(LogisticsDispatchService.class),
                assignments);
    }

    private void assign(PaymentMethod method) {
        when(assignments.findByOrderId(orderId)).thenReturn(Optional.of(OrderAssignment.builder()
                .orderId(orderId).driverId(driverId)
                .state(OrderAssignment.State.ASSIGNED)
                .assignedAt(OffsetDateTime.now())
                .pickupOtp("123456").deliveryOtp("654321")
                .paymentMethod(method)
                .build()));
    }

    private void deliver(BigDecimal cash) {
        strategy.handleStatusUpdate(driverId, orderId, DeliveryStatus.DELIVERED, null, "654321", false, cash);
    }

    @Test
    void aCodDeliveryWithNoDeclaredCashIsRefused() {
        assign(PaymentMethod.COD);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> deliver(null));
        assertTrue(e.getMessage().contains("Declare the cash"), e.getMessage());
        verifyNoInteractions(outbox);
    }

    @Test
    void aNegativeDeclarationIsRefused() {
        assign(PaymentMethod.COD);

        assertThrows(IllegalArgumentException.class, () -> deliver(new BigDecimal("-1.00")));
        verifyNoInteractions(outbox);
    }

    @Test
    void aCodDeliveryWithADeclaredAmountGoesThrough() {
        assign(PaymentMethod.COD);

        try {
            deliver(new BigDecimal("420.00"));
        } catch (RuntimeException e) {
            assertFalse(String.valueOf(e.getMessage()).contains("Declare the cash"), e.getMessage());
        }
        // The outbox write happens after validate() and before the driver lookup this test does not
        // stand up, so seeing it is direct evidence the cash check passed.
        verify(outbox).save(any());
    }

    @Test
    void aPrepaidDeliveryNeedsNoDeclaredCash() {
        assign(PaymentMethod.CARD);

        try {
            deliver(null);
        } catch (RuntimeException e) {
            assertFalse(String.valueOf(e.getMessage()).contains("Declare the cash"), e.getMessage());
        }
        verify(outbox).save(any());
    }

    /** An assignment recorded before the method was known must not silently become "prepaid". */
    @Test
    void anAssignmentWithNoRecordedMethodIsNotTreatedAsCash() {
        assign(null);

        try {
            deliver(null);
        } catch (RuntimeException e) {
            assertFalse(String.valueOf(e.getMessage()).contains("Declare the cash"), e.getMessage());
        }
        verify(outbox).save(any());
    }

    @Test
    void theDeclaredAmountReachesTheOutboxPayload() {
        assign(PaymentMethod.COD);

        try {
            deliver(new BigDecimal("300.00"));
        } catch (RuntimeException ignored) {
            // past validate(), which is what matters
        }

        org.mockito.ArgumentCaptor<com.fooddelivery.common.outbox.entity.OutboxEventEntity> captor =
                org.mockito.ArgumentCaptor.forClass(com.fooddelivery.common.outbox.entity.OutboxEventEntity.class);
        verify(outbox).save(captor.capture());
        assertTrue(captor.getValue().getPayload().contains("300.00"),
                "the customer service books what the rider declared, so it has to travel: "
                        + captor.getValue().getPayload());
    }
}
