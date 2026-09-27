package com.fooddelivery.delivery.service;

import com.fooddelivery.common.constants.RedisKeyConstants;
import com.fooddelivery.common.enums.VerificationStatus;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.enums.DutyChangeReason;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.duty.RiderDutyNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Going on duty is what makes a rider visible to dispatch, and dispatch finds riders by
 * {@code drivers:geo:{city}} intersected with {@code drivers:available:{city}}. A rider who went
 * online without a location was ONLINE in the database and invisible to dispatch.
 */
class RiderGoOnlineTest {

    private static final double LAT = 12.9842;
    private static final double LNG = 77.6658;
    private static final String CITY = "BLR";

    private IDeliveryExecutiveRepository repository;
    private GeoOperations<String, String> geo;
    private SetOperations<String, String> sets;
    private ZSetOperations<String, String> zset;
    private HashOperations<String, Object, Object> hash;
    private RiderDutyNotifier notifier;
    private DeliveryExecutiveProfileService service;
    private DeliveryExecutive rider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        geo = mock(GeoOperations.class);
        sets = mock(SetOperations.class);
        zset = mock(ZSetOperations.class);
        hash = mock(HashOperations.class);
        when(redis.opsForGeo()).thenReturn(geo);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForHash()).thenReturn(hash);

        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));

        rider = new DeliveryExecutive();
        rider.setId(UUID.randomUUID());
        rider.setCityId(CITY);
        rider.setVehicleNumber("KA01AB1234");
        rider.setVerificationStatus(VerificationStatus.APPROVED);
        rider.setActive(true);
        rider.setStatus(DeliveryExecutiveStatus.OFFLINE);

        repository = mock(IDeliveryExecutiveRepository.class);
        when(repository.findById(rider.getId())).thenReturn(Optional.of(rider));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        notifier = mock(RiderDutyNotifier.class);
        service = new DeliveryExecutiveProfileService(tx, repository, redis, notifier);
    }

    @Test
    void goingOnlineWithoutALocationIsRefusedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> service.goOnline(rider.getId(), null, LNG))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Location required");
        assertThatThrownBy(() -> service.goOnline(rider.getId(), LAT, null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(repository, geo, sets, zset, hash, notifier);
        assertThat(rider.getStatus()).isEqualTo(DeliveryExecutiveStatus.OFFLINE);
    }

    @Test
    void goingOnlineWithoutACityIsRefused() {
        rider.setCityId(null);

        assertThatThrownBy(() -> service.goOnline(rider.getId(), LAT, LNG))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("city");

        assertThat(rider.getStatus()).isEqualTo(DeliveryExecutiveStatus.OFFLINE);
        verify(repository, never()).save(any());
        verifyNoInteractions(geo, sets, notifier);
    }

    @Test
    void goingOnlinePlacesTheRiderAtTheirFixInTheGeoIndex() {
        service.goOnline(rider.getId(), LAT, LNG);

        // Redis GEO is (longitude, latitude).
        verify(geo).add("drivers:geo:" + CITY, new Point(LNG, LAT), rider.getId().toString());
    }

    @Test
    void goingOnlineMakesTheRiderDispatchableAndLive() {
        service.goOnline(rider.getId(), LAT, LNG);

        assertThat(rider.getStatus()).isEqualTo(DeliveryExecutiveStatus.ONLINE);
        verify(sets).add(RedisKeyConstants.PREFIX_DRIVERS_AVAILABLE + CITY, rider.getId().toString());
        verify(zset).add(eq("driver_last_ping"), eq(rider.getId().toString()), anyDouble());
        verify(hash).put("drivers:status", rider.getId().toString(), "ONLINE");
        verify(notifier).publish(rider.getId(), DeliveryExecutiveStatus.ONLINE, DutyChangeReason.RIDER_REQUEST);
    }

    /** Re-registering a location mid-delivery must not put a busy rider back into the pool. */
    @Test
    void goingOnlineWhileOnDeliveryRecordsTheFixButDoesNotReopenThePool() {
        rider.setStatus(DeliveryExecutiveStatus.ON_DELIVERY);

        service.goOnline(rider.getId(), LAT, LNG);

        assertThat(rider.getStatus()).isEqualTo(DeliveryExecutiveStatus.ON_DELIVERY);
        verify(geo).add("drivers:geo:" + CITY, new Point(LNG, LAT), rider.getId().toString());
        verify(sets, never()).add(RedisKeyConstants.PREFIX_DRIVERS_AVAILABLE + CITY, rider.getId().toString());
        verify(notifier).publish(rider.getId(), DeliveryExecutiveStatus.ON_DELIVERY, DutyChangeReason.RIDER_REQUEST);
    }

    @Test
    void goingOfflineLeavesThePoolAndTellsTheRiderWhy() {
        rider.setStatus(DeliveryExecutiveStatus.ONLINE);

        service.goOffline(rider.getId(), DutyChangeReason.SUSPENDED);

        assertThat(rider.getStatus()).isEqualTo(DeliveryExecutiveStatus.OFFLINE);
        verify(sets).remove(RedisKeyConstants.PREFIX_DRIVERS_AVAILABLE + CITY, rider.getId().toString());
        verify(zset).remove("driver_last_ping", rider.getId().toString());
        verify(hash).put("drivers:status", rider.getId().toString(), "OFFLINE");
        verify(notifier).publish(rider.getId(), DeliveryExecutiveStatus.OFFLINE, DutyChangeReason.SUSPENDED);
    }

    @Test
    void goingOfflineWhileOnDeliveryIsRefused() {
        rider.setStatus(DeliveryExecutiveStatus.ON_DELIVERY);

        assertThatThrownBy(() -> service.goOffline(rider.getId(), DutyChangeReason.RIDER_REQUEST))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(rider.getStatus()).isEqualTo(DeliveryExecutiveStatus.ON_DELIVERY);
        verifyNoInteractions(notifier);
    }
}
