package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.RedisKeyConstants;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.delivery.client.CustomerServiceClient;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.repository.OrderAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A driver who was never shown an order must not be recorded as rejecting it.
 *
 * <p>Every timed-out candidate used to get a rejection count regardless of whether the ping
 * reached them. Those counts accumulate toward the 5-strike exclusion applied by
 * {@code DelayedDispatchPoller}, so a driver whose socket had dropped was quietly excluded from
 * orders they never had the chance to take -- and, because the ping never arrived, they had no way
 * to know it was happening.
 *
 * <p>{@code CandidateFoundStrategy} now records confirmed deliveries in
 * {@code order:ping:reached:<orderId>}; this pins that {@code timeoutOrderPing} respects it.
 */
class UnreachedDriverNotPenalisedTest {

    private static final UUID ORDER = UUID.fromString("083610e8-d508-4b12-a78b-7c4e13cf3ade");
    private static final String REACHED = "4f4a4e37-6ca5-5598-94f1-43ef1628f631";
    private static final String UNREACHED = "9a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9";

    private StringRedisTemplate redis;
    private SetOperations<String, String> sets;
    private HashOperations<String, Object, Object> hashes;
    private OrderAssignmentService service;

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        sets = mock(SetOperations.class);
        hashes = mock(HashOperations.class);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForZSet()).thenReturn(mock(ZSetOperations.class));

        // Both drivers let the ping lapse.
        when(redis.execute(any(RedisScript.class), anyList()))
                .thenReturn(List.of(REACHED, UNREACHED));

        // Only one of them was actually shown the order.
        String reachedKey = RedisKeyConstants.PREFIX_ORDER_PING_REACHED + ORDER;
        when(sets.isMember(reachedKey, REACHED)).thenReturn(true);
        when(sets.isMember(reachedKey, UNREACHED)).thenReturn(false);

        TransactionTemplate transaction = mock(TransactionTemplate.class);
        service = new OrderAssignmentService(
                redis,
                transaction,
                mock(OutboxEventRepository.class),
                mock(OutboxEventHelper.class),
                mock(IDeliveryExecutiveRepository.class),
                mock(LogisticsDispatchService.class),
                mock(OrderAssignmentRepository.class),
                new ObjectMapper(),
                mock(CustomerServiceClient.class));
    }

    @Test
    void aDriverWhoWasNeverReachedGetsNoRejectionCount() {
        service.timeoutOrderPing(ORDER);

        verify(hashes, never()).increment(
                eq(RedisKeyConstants.PREFIX_ORDER_REJECTED_DRIVERS + ORDER), eq(UNREACHED), anyLong());
    }

    @Test
    void aDriverWhoWasShownTheOrderStillGetsOne() {
        service.timeoutOrderPing(ORDER);

        verify(hashes).increment(
                eq(RedisKeyConstants.PREFIX_ORDER_REJECTED_DRIVERS + ORDER), eq(REACHED), eq(1L));
    }

    /**
     * Nobody rejected -- the window elapsed. The event used to name {@code result.get(0)}, the first
     * driver Redis happened to return from an unordered SMEMBERS, so a named driver was recorded as
     * rejecting an order they had merely not answered, and the choice was not even stable between
     * runs. Neither consumer dereferences driverId (checked: OrderDriverRejectedStrategy and
     * CustomerApplication's OrderEventConsumer use orderId only), and the field carries no
     * {@code @NotNull}, so null binds cleanly.
     */
    @Test
    void aTimeoutNamesNoRejectingDriver() throws Exception {
        org.mockito.ArgumentCaptor<com.fooddelivery.common.outbox.entity.OutboxEventEntity> captor =
                org.mockito.ArgumentCaptor.forClass(com.fooddelivery.common.outbox.entity.OutboxEventEntity.class);
        OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        OutboxEventHelper helper = new OutboxEventHelper(new ObjectMapper());
        TransactionTemplate tx = mock(TransactionTemplate.class);
        org.mockito.Mockito.doAnswer(inv -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> body = inv.getArgument(0);
            body.accept(mock(org.springframework.transaction.TransactionStatus.class));
            return null;
        }).when(tx).executeWithoutResult(any());

        OrderAssignmentService svc = new OrderAssignmentService(
                redis, tx, outbox, helper,
                mock(IDeliveryExecutiveRepository.class),
                mock(LogisticsDispatchService.class),
                mock(OrderAssignmentRepository.class),
                new ObjectMapper(),
                mock(CustomerServiceClient.class));

        svc.timeoutOrderPing(ORDER);

        verify(outbox).save(captor.capture());
        com.fooddelivery.common.event.OrderDriverRejectedEvent published = new ObjectMapper()
                .readValue(captor.getValue().getPayload(),
                        com.fooddelivery.common.event.OrderDriverRejectedEvent.class);
        org.assertj.core.api.Assertions.assertThat(published.getDriverId())
                .describedAs("a lapsed ping is nobody's rejection")
                .isNull();
    }

    /**
     * Availability is not the penalty. Both drivers must go back into the Maps pool -- selection
     * removed them with an atomic SREM, and this is the only path back.
     */
    @Test
    void bothDriversAreStillReleasedBackIntoThePool() {
        LogisticsDispatchService dispatch = mock(LogisticsDispatchService.class);
        OrderAssignmentService svc = new OrderAssignmentService(
                redis,
                mock(TransactionTemplate.class),
                mock(OutboxEventRepository.class),
                mock(OutboxEventHelper.class),
                mock(IDeliveryExecutiveRepository.class),
                dispatch,
                mock(OrderAssignmentRepository.class),
                new ObjectMapper(),
                mock(CustomerServiceClient.class));

        svc.timeoutOrderPing(ORDER);

        verify(dispatch).releaseDriverLock(REACHED);
        verify(dispatch).releaseDriverLock(UNREACHED);
    }
}
