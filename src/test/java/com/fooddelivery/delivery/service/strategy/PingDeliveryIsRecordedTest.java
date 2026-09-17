package com.fooddelivery.delivery.service.strategy;

import com.fooddelivery.common.constants.RedisKeyConstants;
import com.fooddelivery.common.event.DispatchCandidateFoundEvent;
import com.fooddelivery.common.service.NotificationRouterService;
import com.fooddelivery.delivery.websocket.LocationTrackingWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The producer half of the "never reached, never penalised" rule.
 *
 * <p>Only a ping confirmed delivered to a live socket may be recorded in
 * {@code order:ping:reached:<orderId>}; {@code timeoutOrderPing} then counts a rejection for those
 * drivers alone. See UnreachedDriverNotPenalisedTest for the consuming half.
 */
class PingDeliveryIsRecordedTest {

    private static final UUID ORDER = UUID.fromString("083610e8-d508-4b12-a78b-7c4e13cf3ade");
    private static final UUID REACHED = UUID.fromString("4f4a4e37-6ca5-5598-94f1-43ef1628f631");
    private static final UUID UNREACHED = UUID.fromString("9a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9");

    private StringRedisTemplate redis;
    private SetOperations<String, String> sets;
    private LocationTrackingWebSocketHandler sockets;
    private CandidateFoundStrategy strategy;

    private final String reachedKey = RedisKeyConstants.PREFIX_ORDER_PING_REACHED + ORDER;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        sets = mock(SetOperations.class);
        sockets = mock(LocationTrackingWebSocketHandler.class);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(redis.opsForZSet()).thenReturn(mock(ZSetOperations.class));
        when(redis.hasKey(anyString())).thenReturn(false);

        strategy = new CandidateFoundStrategy(redis, mock(NotificationRouterService.class), sockets,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private DispatchCandidateFoundEvent candidatesFound() {
        return DispatchCandidateFoundEvent.builder()
                .orderId(ORDER.toString())
                .driverIds(List.of(REACHED, UNREACHED))
                .build();
    }

    @Test
    void onlyDriversWhosePingLandedAreRecordedAsReached() throws Exception {
        when(sockets.sendPingToDriver(REACHED.toString(), ORDER.toString())).thenReturn(true);
        when(sockets.sendPingToDriver(UNREACHED.toString(), ORDER.toString())).thenReturn(false);

        strategy.handle(candidatesFound(), "DISPATCH_CANDIDATE_FOUND");

        verify(sets).add(reachedKey, REACHED.toString());
        verify(sets, never()).add(eq(reachedKey), eq(UNREACHED.toString()));
    }

    /**
     * Both still get a push. The fallback is the point of sending it; it just cannot report
     * delivery, which is why it does not make a driver "reached".
     */
    @Test
    void everyCandidateStillGetsThePushFallback() throws Exception {
        NotificationRouterService router = mock(NotificationRouterService.class);
        CandidateFoundStrategy s = new CandidateFoundStrategy(redis, router, sockets,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        when(sockets.sendPingToDriver(anyString(), anyString())).thenReturn(false);

        s.handle(candidatesFound(), "DISPATCH_CANDIDATE_FOUND");

        verify(router, org.mockito.Mockito.times(2)).routeNotification(any());
        verify(sets, never()).add(eq(reachedKey), anyString());
    }

    /** A stale set from a previous dispatch cycle must not make this cycle look delivered. */
    @Test
    void theReachedSetIsResetAtTheStartOfEachCycle() throws Exception {
        when(sockets.sendPingToDriver(anyString(), anyString())).thenReturn(false);

        strategy.handle(candidatesFound(), "DISPATCH_CANDIDATE_FOUND");

        verify(redis).delete(reachedKey);
    }
}
