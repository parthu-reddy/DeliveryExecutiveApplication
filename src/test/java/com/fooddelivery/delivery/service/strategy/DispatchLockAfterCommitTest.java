package com.fooddelivery.delivery.service.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.common.constants.RedisKeyConstants;
import com.fooddelivery.delivery.service.LogisticsDispatchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The dispatch lock is taken after the consumer's transaction commits, not inside it.
 *
 * <p>M-13. `OrderEventConsumer` runs this strategy inside `transactionTemplate.execute`, in the same
 * transaction as the idempotency claim. Taking the Redis lock there meant a failure at *commit*
 * rolled the claim back and left the lock set: the redelivery then logged "Duplicate ORDER_ACCEPTED
 * dispatch event ignored" and the order was never dispatched, by anyone, silently.
 *
 * <p>This is a behavioural test on purpose. Validator check 7.9 can only see that the source
 * mentions `afterCommit`; it cannot see whether the call is actually reached that way, and a
 * break-test that routed the dispatch back inline (`if (false)`) left the check green. A scan
 * cannot evaluate a condition -- the Phase 4 lesson -- so the guard needs a test that runs it.
 */
class DispatchLockAfterCommitTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private LogisticsDispatchService dispatchService;
    private OrderAcceptedStrategy strategy;

    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForZSet()).thenReturn(mock(ZSetOperations.class));
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        dispatchService = mock(LogisticsDispatchService.class);
        strategy = new OrderAcceptedStrategy(dispatchService, redisTemplate);

        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private com.fasterxml.jackson.databind.JsonNode event() throws Exception {
        return new ObjectMapper().readTree("{"
                + "\"orderId\":\"" + orderId + "\","
                + "\"restaurantLat\":12.9,\"restaurantLng\":77.6,"
                + "\"deliveryLat\":12.95,\"deliveryLng\":77.65,"
                + "\"deliveryAddress\":\"1 Test Road\","
                + "\"estimatedCompletionTime\":0}");
    }

    @Test
    void noRedisLockIsTakenWhileTheTransactionIsStillOpen() throws Exception {
        strategy.process(event(), EventType.ORDER_ACCEPTED.name());

        // Nothing has committed yet: a lock taken here would survive a rollback that discards the
        // idempotency claim, and the redelivery would be ignored as a duplicate.
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(dispatchService, never())
                .dispatchNearestDriver(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString(), any(), any());
        assertTrue(TransactionSynchronizationManager.getSynchronizations().size() >= 1,
                "the strategy must register a synchronization rather than acting inline");
    }

    @Test
    void theLockAndTheDispatchHappenOnceTheTransactionCommits() throws Exception {
        strategy.process(event(), EventType.ORDER_ACCEPTED.name());

        TransactionSynchronizationUtils.triggerAfterCommit();

        verify(valueOps).setIfAbsent(
                eq(RedisKeyConstants.PREFIX_ORDER_DISPATCH_LOCK + orderId), eq("locked"), any(Duration.class));
        verify(dispatchService).dispatchNearestDriver(
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString(), eq(orderId), any());
    }

    @Test
    void aSecondDeliveryOfTheSameEventIsIgnored() throws Exception {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        strategy.process(event(), EventType.ORDER_ACCEPTED.name());
        TransactionSynchronizationUtils.triggerAfterCommit();

        verify(valueOps).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(dispatchService, never())
                .dispatchNearestDriver(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString(), any(), any());
    }

    private static double anyDouble() {
        return org.mockito.ArgumentMatchers.anyDouble();
    }
}
