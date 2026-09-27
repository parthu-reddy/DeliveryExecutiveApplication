package com.fooddelivery.delivery.scheduler;

import com.fooddelivery.common.constants.RedisKeyConstants;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.enums.DutyChangeReason;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.duty.RiderDutyNotifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sweeper decides who dispatch can still see. These pin the cases the old Redis-driven sweep got
 * wrong: an ONLINE row with no ping entry was never examined, and a rider carrying an order was set
 * OFFLINE mid-delivery.
 */
class StaleDriverSweeperDaemonTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long STALE = NOW - StaleDriverSweeperDaemon.STALE_THRESHOLD_MS - 1;
    private static final long FRESH = NOW - 1_000;
    private static final String CITY = "BLR";

    private final Map<UUID, DeliveryExecutive> rows = new HashMap<>();
    private final Map<String, Double> pings = new HashMap<>();

    private IDeliveryExecutiveRepository repository;
    private ZSetOperations<String, String> zset;
    private SetOperations<String, String> sets;
    private GeoOperations<String, String> geo;
    private HashOperations<String, Object, Object> hash;
    private RiderDutyNotifier notifier;
    private StaleDriverSweeperDaemon sweeper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        zset = mock(ZSetOperations.class);
        sets = mock(SetOperations.class);
        geo = mock(GeoOperations.class);
        hash = mock(HashOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForGeo()).thenReturn(geo);
        when(redis.opsForHash()).thenReturn(hash);
        when(zset.rangeWithScores("driver_last_ping", 0, -1)).thenAnswer(inv -> {
            Set<ZSetOperations.TypedTuple<String>> out = new LinkedHashSet<>();
            pings.forEach((k, v) -> out.add(new DefaultTypedTuple<>(k, v)));
            return out;
        });
        when(zset.score(eq("driver_last_ping"), anyString())).thenAnswer(inv -> pings.get((String) inv.getArgument(1)));

        repository = mock(IDeliveryExecutiveRepository.class);
        when(repository.findByStatus(DeliveryExecutiveStatus.ONLINE)).thenAnswer(inv ->
                rows.values().stream().filter(r -> r.getStatus() == DeliveryExecutiveStatus.ONLINE).toList());
        when(repository.findLockedById(any())).thenAnswer(inv -> Optional.ofNullable(rows.get((UUID) inv.getArgument(0))));
        when(repository.findAllById(any())).thenAnswer(inv -> {
            List<DeliveryExecutive> out = new ArrayList<>();
            ((Iterable<UUID>) inv.getArgument(0)).forEach(id -> { if (rows.containsKey(id)) out.add(rows.get(id)); });
            return out;
        });
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));

        notifier = mock(RiderDutyNotifier.class);
        sweeper = new StaleDriverSweeperDaemon(redis, new SimpleMeterRegistry(), repository, tx, notifier);
    }

    private UUID rider(DeliveryExecutiveStatus status, Double lastPing) {
        UUID id = UUID.randomUUID();
        DeliveryExecutive e = new DeliveryExecutive();
        e.setId(id);
        e.setCityId(CITY);
        e.setStatus(status);
        rows.put(id, e);
        if (lastPing != null) pings.put(id.toString(), lastPing);
        return id;
    }

    @Test
    void onlineRiderWithAStalePingIsDemotedRemovedFromDispatchAndTold() {
        UUID id = rider(DeliveryExecutiveStatus.ONLINE, (double) STALE);

        sweeper.sweep(NOW);

        assertThat(rows.get(id).getStatus()).isEqualTo(DeliveryExecutiveStatus.OFFLINE);
        verify(sets).remove(RedisKeyConstants.PREFIX_DRIVERS_AVAILABLE + CITY, id.toString());
        verify(geo).remove("drivers:geo:" + CITY, id.toString());
        verify(zset).remove("driver_last_ping", id.toString());
        verify(hash).put("drivers:status", id.toString(), "OFFLINE");
        verify(notifier).publish(id, DeliveryExecutiveStatus.OFFLINE, DutyChangeReason.LOCATION_LOST);
    }

    /** The ghost: ONLINE in the database, nothing in the ping set. The old sweep never saw it. */
    @Test
    void onlineRiderWithNoPingEntryIsDemoted() {
        UUID id = rider(DeliveryExecutiveStatus.ONLINE, null);

        sweeper.sweep(NOW);

        assertThat(rows.get(id).getStatus()).isEqualTo(DeliveryExecutiveStatus.OFFLINE);
        verify(notifier).publish(id, DeliveryExecutiveStatus.OFFLINE, DutyChangeReason.LOCATION_LOST);
    }

    @Test
    void riderOnDeliveryWithAStalePingKeepsTheirStatusAndTheirPingEntry() {
        UUID id = rider(DeliveryExecutiveStatus.ON_DELIVERY, (double) STALE);

        sweeper.sweep(NOW);

        assertThat(rows.get(id).getStatus()).isEqualTo(DeliveryExecutiveStatus.ON_DELIVERY);
        verify(zset, never()).remove("driver_last_ping", id.toString());
        verify(repository, never()).save(any());
        verify(notifier, never()).publish(any(), any(), any());
    }

    @Test
    void onlineRiderWithAFreshPingIsUntouched() {
        UUID id = rider(DeliveryExecutiveStatus.ONLINE, (double) FRESH);

        sweeper.sweep(NOW);

        assertThat(rows.get(id).getStatus()).isEqualTo(DeliveryExecutiveStatus.ONLINE);
        verify(repository, never()).save(any());
        verify(notifier, never()).publish(any(), any(), any());
    }

    @Test
    void staleEntriesOfOfflineRidersAndUnparseableIdsAreRemoved() {
        UUID offline = rider(DeliveryExecutiveStatus.OFFLINE, (double) STALE);
        pings.put("not-a-uuid", (double) STALE);

        sweeper.sweep(NOW);

        verify(zset).remove("driver_last_ping", offline.toString());
        verify(zset).remove("driver_last_ping", "not-a-uuid");
        verify(repository, never()).save(any());
    }

    /** Scan saw a stale ping; by the time the row is locked the rider has pinged again. */
    @Test
    void riderWhoPingedBetweenTheScanAndTheLockIsNotDemoted() {
        UUID id = rider(DeliveryExecutiveStatus.ONLINE, (double) STALE);
        when(repository.findLockedById(id)).thenAnswer(inv -> {
            pings.put(id.toString(), (double) FRESH);
            return Optional.of(rows.get(id));
        });

        sweeper.sweep(NOW);

        assertThat(rows.get(id).getStatus()).isEqualTo(DeliveryExecutiveStatus.ONLINE);
        verify(notifier, never()).publish(any(), any(), any());
    }

    @Test
    void oneRiderFailingToSaveDoesNotStopTheRestOfTheSweep() {
        UUID first = rider(DeliveryExecutiveStatus.ONLINE, (double) STALE);
        UUID second = rider(DeliveryExecutiveStatus.ONLINE, (double) STALE);
        when(repository.save(any())).thenAnswer(inv -> {
            DeliveryExecutive e = inv.getArgument(0);
            if (e.getId().equals(first)) {
                e.setStatus(DeliveryExecutiveStatus.ONLINE); // the failed transaction rolled back
                throw new org.springframework.orm.ObjectOptimisticLockingFailureException(DeliveryExecutive.class, first);
            }
            return e;
        });

        sweeper.sweep(NOW);

        assertThat(rows.get(second).getStatus()).isEqualTo(DeliveryExecutiveStatus.OFFLINE);
        verify(notifier).publish(second, DeliveryExecutiveStatus.OFFLINE, DutyChangeReason.LOCATION_LOST);
        verify(notifier, never()).publish(eq(first), any(), any());
    }
}
