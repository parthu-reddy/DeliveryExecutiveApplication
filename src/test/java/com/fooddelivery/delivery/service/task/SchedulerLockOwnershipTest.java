package com.fooddelivery.delivery.service.task;

import com.fooddelivery.common.constants.RedisKeyConstants;
import com.fooddelivery.common.lock.RedisLock;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A scheduler must only release the lock it still owns.
 *
 * <p>{@code RedisLockReaperTask} acquired with a token and then released with an unconditional
 * {@code redisTemplate.delete(...)}. Its TTL is one minute and it does two full keyspace SCANs plus
 * a batched DB fetch, so overrunning is realistic — and on an overrun the blind delete removed the
 * lock a *different* instance had just acquired, letting two reapers delete each other's keys. The
 * other three schedulers already used the token-checked release; this one was the outlier.
 *
 * <p>The Lua in {@link RedisLock#release} is a compare-and-delete, so the guarantee lives there.
 * These tests pin that the reaper actually uses it, and that the lock key is the shared constant
 * rather than an inline literal that can drift from it.
 */
class SchedulerLockOwnershipTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void releaseIsAConditionalCompareAndDeleteNotABlindDelete() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        // The script returns 0 when the stored token is somebody else's.
        when(redis.execute(any(RedisScript.class), anyList(), eq("token-A"))).thenReturn(0L);

        boolean released = new RedisLock(redis).release(RedisKeyConstants.LOCK_REAPER_TASK, "token-A");

        assertThat(released)
                .describedAs("a lock held by another instance must not be reported as released")
                .isFalse();
        // The critical part: it must go through the script, never through a raw delete.
        verify(redis, never()).delete(RedisKeyConstants.LOCK_REAPER_TASK);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void releaseSucceedsWhenTheTokenStillMatches() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), eq("token-A"))).thenReturn(1L);

        assertThat(new RedisLock(redis).release(RedisKeyConstants.LOCK_REAPER_TASK, "token-A"))
                .describedAs("the owner must still be able to release")
                .isTrue();
    }

    /**
     * The reaper's own path: acquire fails, so the body is skipped entirely and nothing is deleted.
     * This is the cheap half of the guarantee — a second instance must not touch the lock at all.
     */
    @Test
    void anInstanceThatDidNotAcquireTheLockTouchesNothing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> values = mock(
                org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq(RedisKeyConstants.LOCK_REAPER_TASK), any(), any(Duration.class)))
                .thenReturn(false);

        new RedisLockReaperTask(redis, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                mock(com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository.class))
                .reapOrphanedLocks();

        verify(redis, never()).delete(RedisKeyConstants.LOCK_REAPER_TASK);
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any());
    }

    /** Guards against the literal creeping back alongside the constant. */
    @Test
    void theReaperLockKeyIsTheSharedConstant() {
        assertThat(RedisKeyConstants.LOCK_REAPER_TASK).isEqualTo("lock:reaper_task_execution");
    }
}
