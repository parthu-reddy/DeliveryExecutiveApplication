package com.fooddelivery.delivery.service.location;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.enums.DutyChangeReason;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.duty.RiderDutyDemotion;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The guard's rules, against an in-memory value store and a clock the test moves. */
class RiderLocationGuardTest {

    private static final UUID RIDER = UUID.fromString("4f4a4e37-6ca5-5598-94f1-43ef1628f631");
    // Indiranagar → a point 1.1 km east; Bengaluru → Mumbai is ~840 km.
    private static final double LAT = 12.9784, LNG = 77.6408;
    private static final double LAT_NEAR = 12.9784, LNG_NEAR = 77.6510;
    private static final double MUMBAI_LAT = 19.0760, MUMBAI_LNG = 72.8777;

    private final Map<String, String> values = new HashMap<>();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-11T05:00:00Z"));
    private StringRedisTemplate redis;
    private GeoOperations<String, String> geo;
    private IDeliveryExecutiveRepository repository;
    private RiderDutyDemotion demotion;
    private RiderLocationGuard guard;
    private DeliveryExecutive rider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        geo = mock(GeoOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(redis.opsForGeo()).thenReturn(geo);
        when(redis.opsForZSet()).thenReturn(mock(ZSetOperations.class));
        when(ops.get(anyString())).thenAnswer(inv -> values.get(inv.<String>getArgument(0)));
        org.mockito.Mockito.doAnswer(inv -> values.put(inv.getArgument(0), inv.getArgument(1)))
                .when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.increment(anyString())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            long n = Long.parseLong(values.getOrDefault(k, "0")) + 1;
            values.put(k, Long.toString(n));
            return n;
        });
        when(redis.delete(anyString())).thenAnswer(inv -> values.remove(inv.<String>getArgument(0)) != null);

        rider = new DeliveryExecutive();
        rider.setId(RIDER);
        rider.setCityId("BLR");
        rider.setStatus(DeliveryExecutiveStatus.ONLINE);
        repository = mock(IDeliveryExecutiveRepository.class);
        when(repository.findById(RIDER)).thenAnswer(inv -> Optional.of(rider));
        demotion = mock(RiderDutyDemotion.class);
        guard = new RiderLocationGuard(redis, repository, demotion, new SimpleMeterRegistry(), clock);
    }

    @Test
    void acceptsPlausibleMove() {
        guard.startFresh(rider, LAT, LNG);
        clock.advance(Duration.ofMinutes(1)); // 1.1 km in a minute ≈ 66 km/h

        Optional<RiderLocationGuard.Fix> fix = guard.accept(RIDER, LAT_NEAR, LNG_NEAR);

        assertThat(fix).isPresent();
        assertThat(fix.get().observedAtMs()).isEqualTo(clock.millis());
        verify(geo).add(eq("drivers:geo:BLR"), eq(new Point(LNG_NEAR, LAT_NEAR)), eq(RIDER.toString()));
    }

    @Test
    void dropsImpossibleJump() {
        guard.startFresh(rider, LAT, LNG);
        clock.advance(Duration.ofSeconds(5));

        assertThat(guard.accept(RIDER, MUMBAI_LAT, MUMBAI_LNG)).isEmpty();

        verify(geo, never()).add(eq("drivers:geo:BLR"), eq(new Point(MUMBAI_LNG, MUMBAI_LAT)), anyString());
        assertThat(values.get("driver:spoof_strikes:" + RIDER)).isEqualTo("1");
        assertThat(values.get("driver:last_fix:" + RIDER)).startsWith(LAT + "," + LNG + ",");
    }

    @Test
    void jitterUnderThresholdIsAccepted() {
        guard.startFresh(rider, LAT, LNG);
        // 200 m in the same millisecond: infinite speed, but within GPS jitter of a parked rider.
        assertThat(guard.accept(RIDER, LAT + 0.0018, LNG)).isPresent();
        assertThat(values).doesNotContainKey("driver:spoof_strikes:" + RIDER);
    }

    @Test
    void thirdStrikeTakesOnlineRiderOffline() {
        guard.startFresh(rider, LAT, LNG);
        when(demotion.demote(eq(RIDER), any(), eq(DutyChangeReason.LOCATION_IMPLAUSIBLE))).thenReturn(rider);
        for (int i = 0; i < 2; i++) {
            clock.advance(Duration.ofSeconds(5));
            guard.accept(RIDER, MUMBAI_LAT, MUMBAI_LNG);
        }
        verify(demotion, never()).demote(any(), any(), any());

        clock.advance(Duration.ofSeconds(5));
        guard.accept(RIDER, MUMBAI_LAT, MUMBAI_LNG);

        verify(demotion).demote(eq(RIDER), any(), eq(DutyChangeReason.LOCATION_IMPLAUSIBLE));
        assertThat(values).doesNotContainKey("driver:spoof_strikes:" + RIDER);
    }

    @Test
    void riderOnDeliveryIsNeverDemoted() {
        guard.startFresh(rider, LAT, LNG);
        // The demotion refuses anyone not ONLINE under the lock (RiderDutyDemotion), returning null.
        when(demotion.demote(any(), any(), any())).thenReturn(null);
        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofSeconds(5));
            assertThat(guard.accept(RIDER, MUMBAI_LAT, MUMBAI_LNG)).isEmpty();
        }
        verify(demotion, times(2)).demote(eq(RIDER), any(), eq(DutyChangeReason.LOCATION_IMPLAUSIBLE));
        verify(geo, never()).add(anyString(), eq(new Point(MUMBAI_LNG, MUMBAI_LAT)), anyString());
    }

    @Test
    void cityComesFromProfileNotTheApp() {
        rider.setCityId("HYD");
        // No cached city: read from the profile, cached, and used for the geo set.
        assertThat(guard.accept(RIDER, LAT, LNG)).get().extracting(RiderLocationGuard.Fix::cityId).isEqualTo("HYD");
        verify(geo).add(eq("drivers:geo:HYD"), any(Point.class), eq(RIDER.toString()));
        assertThat(values.get("driver:city:" + RIDER)).isEqualTo("HYD");

        rider.setStatus(DeliveryExecutiveStatus.OFFLINE);
        values.remove("driver:city:" + RIDER);
        assertThat(guard.accept(RIDER, LAT, LNG)).as("an off-duty rider is placed nowhere").isEmpty();
    }

    @Test
    void rejectsInvalidCoordinates() {
        guard.startFresh(rider, LAT, LNG);
        for (double[] p : new double[][]{{Double.NaN, LNG}, {LAT, Double.POSITIVE_INFINITY}, {91, LNG}, {LAT, -181}, {0, 0}}) {
            assertThat(guard.accept(RIDER, p[0], p[1])).as("%s,%s", p[0], p[1]).isEmpty();
        }
        assertThat(values).doesNotContainKey("driver:spoof_strikes:" + RIDER);
        assertThatThrownBy(() -> guard.startFresh(rider, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
