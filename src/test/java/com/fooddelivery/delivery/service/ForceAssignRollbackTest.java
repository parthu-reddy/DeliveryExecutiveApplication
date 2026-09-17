package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.RedisKeyConstants;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.delivery.client.CustomerServiceClient;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.repository.OrderAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A force-assign that fails must leave nothing behind that silences re-dispatch.
 *
 * <p>The method used to write three Redis keys before opening its transaction and delete only one
 * of them when it failed. The survivor that mattered was {@code order:dispatch:lock:<id>} set to
 * {@code "CANCELLED"} for 24 hours: {@code DelayedDispatchPoller} and
 * {@code OrderDriverRejectedStrategy} both read that key and refuse to re-dispatch, and
 * {@code RedisLockReaperTask} never scans it. So an admin mistyping a driver id quietly switched
 * off every automatic path that would have found another driver, for a day.
 *
 * <p>The first statement inside the transaction is the driver lookup, so "driver not found" is the
 * realistic trigger, not a contrived one.
 *
 * <p>The success test is not decoration: without it, deleting the writes altogether would satisfy
 * the failure test while breaking force-assign.
 */
class ForceAssignRollbackTest {

    private static final UUID ORDER = UUID.fromString("083610e8-d508-4b12-a78b-7c4e13cf3ade");
    private static final UUID DRIVER = UUID.fromString("4f4a4e37-6ca5-5598-94f1-43ef1628f631");

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private IDeliveryExecutiveRepository repository;
    private OrderAssignmentService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(mock(SetOperations.class));
        when(redis.opsForZSet()).thenReturn(mock(ZSetOperations.class));
        when(redis.opsForHash()).thenReturn(mock(HashOperations.class));

        repository = mock(IDeliveryExecutiveRepository.class);

        // Run the transaction body inline so its failure surfaces the way it would in production.
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        doAnswer(inv -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> body =
                    inv.getArgument(0);
            body.accept(mock(org.springframework.transaction.TransactionStatus.class));
            return null;
        }).when(transaction).executeWithoutResult(any());

        service = new OrderAssignmentService(
                redis,
                transaction,
                mock(OutboxEventRepository.class),
                mock(OutboxEventHelper.class),
                repository,
                mock(LogisticsDispatchService.class),
                mock(OrderAssignmentRepository.class),
                new ObjectMapper(),
                mock(CustomerServiceClient.class));
    }

    @Test
    void aFailedForceAssignWritesNoKeyThatBlocksRedispatch() {
        when(repository.findLockedById(DRIVER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.forceAssignOrder(ORDER, DRIVER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Driver not found");

        // The key that silences DelayedDispatchPoller and OrderDriverRejectedStrategy for 24h.
        verify(values, never()).set(
                eq(RedisKeyConstants.PREFIX_ORDER_DISPATCH_LOCK + ORDER), anyString(), any(Duration.class));
        // The key that makes the driver look busy.
        verify(values, never()).set(
                eq(RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER + DRIVER), anyString(), any(Duration.class));
        // The mutual-exclusion lock is taken before the transaction on purpose, and released here.
        verify(redis).delete(RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + ORDER);
    }

    @Test
    void aSuccessfulForceAssignStillSetsAllThreeKeys() {
        DeliveryExecutive executive = new DeliveryExecutive();
        executive.setId(DRIVER);
        executive.setStatus(DeliveryExecutiveStatus.ONLINE);
        when(repository.findLockedById(DRIVER)).thenReturn(Optional.of(executive));
        when(repository.findById(DRIVER)).thenReturn(Optional.of(executive));

        service.forceAssignOrder(ORDER, DRIVER);

        verify(values).set(eq(RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + ORDER),
                eq(DRIVER.toString()), any(Duration.class));
        verify(values).set(eq(RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER + DRIVER),
                eq(ORDER.toString()), any(Duration.class));
        verify(values).set(eq(RedisKeyConstants.PREFIX_ORDER_DISPATCH_LOCK + ORDER),
                eq("CANCELLED"), any(Duration.class));
    }

    /** An offline driver being force-assigned is brought online; that promotion is intended. */
    @Test
    void anOfflineDriverIsBroughtOnline() {
        DeliveryExecutive executive = new DeliveryExecutive();
        executive.setId(DRIVER);
        executive.setStatus(DeliveryExecutiveStatus.OFFLINE);
        when(repository.findLockedById(DRIVER)).thenReturn(Optional.of(executive));
        when(repository.findById(DRIVER)).thenReturn(Optional.of(executive));

        service.forceAssignOrder(ORDER, DRIVER);

        org.assertj.core.api.Assertions.assertThat(executive.getStatus())
                .isNotEqualTo(DeliveryExecutiveStatus.OFFLINE);
    }
}
