package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.dto.maps.LiveRiderLocation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the writer hands Redis, and every position it refuses before Redis is asked. */
class RiderLocationSnapshotWriterTest {

    private static final long NOW = 1_800_000_000_000L;

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sendsTheAssignmentGuardKeysTheSnapshotAndTheRemainingLifetime() throws Exception {
        RiderLocationSnapshotWriter.write(redis, mapper, "rider-1", "order-1", "BLR", 12.97, 77.59, NOW - 5_000, NOW);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(redis).execute(any(RedisScript.class),
                eq(List.of("driver:active_order:rider-1", "tracking:order-location:order-1")),
                eq("order-1"), json.capture(), eq(Long.toString(NOW - 5_000)), eq("25000"));
        assertThat(mapper.readValue(json.getValue(), LiveRiderLocation.class))
                .isEqualTo(new LiveRiderLocation("rider-1", "order-1", "BLR", 12.97, 77.59, NOW - 5_000));
    }

    @Test
    void aFixAsOldAsTheWindowIsNotWritten() {
        RiderLocationSnapshotWriter.write(redis, mapper, "rider-1", "order-1", "BLR", 12.97, 77.59,
                NOW - LiveRiderLocation.MAX_AGE_MS, NOW);
        assertThat(scriptCalls()).isZero();
    }

    @Test
    void aFixFromTheFutureIsNotWritten() {
        RiderLocationSnapshotWriter.write(redis, mapper, "rider-1", "order-1", "BLR", 12.97, 77.59, NOW + 1, NOW);
        assertThat(scriptCalls()).isZero();
    }

    @Test
    void impossibleCoordinatesAreNotWritten() {
        RiderLocationSnapshotWriter.write(redis, mapper, "rider-1", "order-1", "BLR", 91, 77.59, NOW, NOW);
        RiderLocationSnapshotWriter.write(redis, mapper, "rider-1", "order-1", "BLR", 12.97, Double.NaN, NOW, NOW);
        RiderLocationSnapshotWriter.write(redis, mapper, "rider-1", "order-1", "BLR", 12.97, 180.5, NOW, NOW);
        assertThat(scriptCalls()).isZero();
    }

    @Test
    void noOrderNoRiderOrNoCityIsNotWritten() {
        RiderLocationSnapshotWriter.write(redis, mapper, "rider-1", " ", "BLR", 12.97, 77.59, NOW, NOW);
        RiderLocationSnapshotWriter.write(redis, mapper, "rider-1", null, "BLR", 12.97, 77.59, NOW, NOW);
        RiderLocationSnapshotWriter.write(redis, mapper, null, "order-1", "BLR", 12.97, 77.59, NOW, NOW);
        RiderLocationSnapshotWriter.write(redis, mapper, "rider-1", "order-1", "", 12.97, 77.59, NOW, NOW);
        assertThat(scriptCalls()).isZero();
    }

    @Test
    void aRedisFailureDoesNotEscapeIntoTheTelemetryPath() {
        when(redis.execute(any(RedisScript.class), any(List.class), any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> RiderLocationSnapshotWriter.write(redis, mapper, "rider-1", "order-1", "BLR",
                12.97, 77.59, NOW, NOW)).doesNotThrowAnyException();
        assertThat(scriptCalls()).isEqualTo(1);
    }

    private long scriptCalls() {
        return mockingDetails(redis).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("execute") && i.getArgument(0) instanceof RedisScript)
                .count();
    }
}
