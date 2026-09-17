package com.fooddelivery.delivery.scheduler;

import com.fooddelivery.common.constants.RedisKeyConstants;
import com.fooddelivery.delivery.service.OrderAssignmentService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Capping the batch must not strand a backlog.
 *
 * <p>{@code DriverPingTimeoutPoller} used to pull every due order in one poll while holding a
 * 4-second lock, which the work routinely outlived. The cap (50) bounds the work so the TTL can
 * actually cover it — but a cap is only safe if the remainder is picked up on the following ticks.
 * Without this test, capping silently converts "slow" into "never" for anything past the 50th
 * entry, and nothing would go red.
 */
class CappedBatchDrainsTest {

    private static final int CAP = 50;

    @Test
    void aBacklogLargerThanTheCapDrainsAcrossSuccessiveTicks() {
        // A stand-in for the ZSET: each poll takes at most CAP, and processing removes them.
        Set<String> backlog = new LinkedHashSet<>();
        for (int i = 0; i < CAP * 3 + 7; i++) {
            backlog.add(UUID.nameUUIDFromBytes(("order-" + i).getBytes()).toString());
        }
        int initial = backlog.size();

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForZSet()).thenReturn(zset);
        // Single instance: the lock is always free.
        when(values.setIfAbsent(eq(RedisKeyConstants.LOCK_POLL_PING_TIMEOUTS), any(), any(Duration.class)))
                .thenReturn(true);

        when(zset.rangeByScore(eq(RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS),
                anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    long count = inv.getArgument(4);
                    return backlog.stream().limit(count)
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                });

        AtomicInteger processed = new AtomicInteger();
        OrderAssignmentService assignments = mock(OrderAssignmentService.class);
        org.mockito.Mockito.doAnswer(inv -> {
            backlog.remove(inv.getArgument(0).toString());   // the real timeout path clears the entry
            processed.incrementAndGet();
            return null;
        }).when(assignments).timeoutOrderPing(any(UUID.class));

        DriverPingTimeoutPoller poller = new DriverPingTimeoutPoller(redis, assignments);

        // Enough ticks to clear it, and no more. ceil(157/50) = 4.
        int ticks = (initial + CAP - 1) / CAP;
        for (int i = 0; i < ticks; i++) {
            poller.pollPingTimeouts();
        }

        assertThat(backlog)
                .describedAs("a capped poll must keep draining on later ticks, not strand the tail")
                .isEmpty();
        assertThat(processed.get()).isEqualTo(initial);
    }

    @Test
    void oneTickTakesNoMoreThanTheCap() {
        Set<String> backlog = new LinkedHashSet<>();
        for (int i = 0; i < CAP * 2; i++) {
            backlog.add(UUID.nameUUIDFromBytes(("o" + i).getBytes()).toString());
        }

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForZSet()).thenReturn(zset);
        when(values.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(true);
        when(zset.rangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenAnswer(inv -> backlog.stream().limit((long) inv.getArgument(4))
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));

        AtomicInteger processed = new AtomicInteger();
        OrderAssignmentService assignments = mock(OrderAssignmentService.class);
        org.mockito.Mockito.doAnswer(inv -> { processed.incrementAndGet(); return null; })
                .when(assignments).timeoutOrderPing(any(UUID.class));

        new DriverPingTimeoutPoller(redis, assignments).pollPingTimeouts();

        assertThat(processed.get())
                .describedAs("the cap is what lets the lock TTL cover the work")
                .isEqualTo(CAP);
    }
}
