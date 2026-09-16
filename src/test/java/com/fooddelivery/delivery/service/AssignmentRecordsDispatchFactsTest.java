package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.RedisKeyConstants;
import com.fooddelivery.common.enums.PaymentMethod;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.delivery.client.CustomerServiceClient;
import com.fooddelivery.delivery.entity.OrderAssignment;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.repository.OrderAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The facts lifted out of the ORDER_ACCEPTED payload actually land on the assignment row.
 *
 * <p>Written because a break-test removed the `paymentMethod` assignment in `recordAssignment` and
 * every test still passed: `CodDeliveryRequiresDeclaredCashTest` builds its own assignment with the
 * field already set, so it could never see the wiring break. The OTPs have the same shape — they
 * are read here and read back at handover, and nothing else joins the two.
 */
class AssignmentRecordsDispatchFactsTest {

    private OrderAssignmentRepository assignments;
    private ValueOperations<String, String> values;
    private OrderAssignmentService service;

    private final UUID orderId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        assignments = mock(OrderAssignmentRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        lenient().when(redis.opsForValue()).thenReturn(values);
        when(assignments.findByOrderId(orderId)).thenReturn(Optional.empty());

        service = new OrderAssignmentService(redis, mock(TransactionTemplate.class),
                mock(OutboxEventRepository.class), mock(OutboxEventHelper.class),
                mock(IDeliveryExecutiveRepository.class), mock(LogisticsDispatchService.class),
                assignments, new ObjectMapper(), mock(CustomerServiceClient.class));
    }

    private void dispatchPayload(String json) {
        when(values.get(RedisKeyConstants.PREFIX_ORDER_DISPATCH_PAYLOAD + orderId)).thenReturn(json);
    }

    private OrderAssignment recorded() {
        ArgumentCaptor<OrderAssignment> captor = ArgumentCaptor.forClass(OrderAssignment.class);
        verify(assignments).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void theOtpsAndThePaymentMethodAreAllRecorded() {
        dispatchPayload("{\"pickupOtp\":\"111111\",\"deliveryOtp\":\"222222\",\"paymentMethod\":\"UPI\"}");

        service.recordAssignment(orderId, driverId);

        OrderAssignment a = recorded();
        assertEquals(orderId, a.getOrderId());
        assertEquals(driverId, a.getDriverId());
        assertEquals("111111", a.getPickupOtp());
        assertEquals("222222", a.getDeliveryOtp());
        assertEquals(PaymentMethod.UPI, a.getPaymentMethod());
        assertEquals(OrderAssignment.State.ASSIGNED, a.getState());
        assertNotNull(a.getAssignedAt());
        assertNull(a.getReleasedAt());
    }

    @Test
    void aPrepaidOrderIsRecordedAsSuch() {
        dispatchPayload("{\"pickupOtp\":\"111111\",\"deliveryOtp\":\"222222\",\"paymentMethod\":\"CARD\"}");

        service.recordAssignment(orderId, driverId);

        assertEquals(PaymentMethod.CARD, recorded().getPaymentMethod());
    }

    @Test
    void aMissingPayloadStillRecordsTheAssignment() {
        dispatchPayload(null);

        service.recordAssignment(orderId, driverId);

        OrderAssignment a = recorded();
        assertEquals(driverId, a.getDriverId(),
                "an assignment with no OTPs denies the handover, which is the safe direction; "
                        + "not recording it at all would mean nobody is authorised");
        assertNull(a.getPickupOtp());
        assertNull(a.getPaymentMethod());
    }

    @Test
    void anUnknownPaymentMethodIsLeftNullRatherThanGuessed() {
        dispatchPayload("{\"pickupOtp\":\"111111\",\"deliveryOtp\":\"222222\",\"paymentMethod\":\"BARTER\"}");

        service.recordAssignment(orderId, driverId);

        assertNull(recorded().getPaymentMethod());
    }

    @Test
    void reassigningTheSameOrderReplacesTheDriverAndClearsTheRelease() {
        OrderAssignment existing = OrderAssignment.builder()
                .orderId(orderId).driverId(UUID.randomUUID())
                .state(OrderAssignment.State.RELEASED)
                .releasedAt(java.time.OffsetDateTime.now())
                .pickupOtp("999999").deliveryOtp("888888")
                .paymentMethod(PaymentMethod.CARD)
                .build();
        when(assignments.findByOrderId(orderId)).thenReturn(Optional.of(existing));
        dispatchPayload(null);

        service.recordAssignment(orderId, driverId);

        OrderAssignment a = recorded();
        assertEquals(driverId, a.getDriverId());
        assertEquals(OrderAssignment.State.ASSIGNED, a.getState());
        assertNull(a.getReleasedAt());
        assertEquals("999999", a.getPickupOtp(),
                "a redispatch with no payload must not erase the OTPs the first dispatch recorded");
    }
}
