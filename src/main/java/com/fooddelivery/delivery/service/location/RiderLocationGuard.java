package com.fooddelivery.delivery.service.location;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.enums.DutyChangeReason;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.RiderLiveness;
import com.fooddelivery.delivery.service.duty.RiderDutyDemotion;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * The only writer of rider positions into dispatch's {@code drivers:geo:<city>} and {@code driver_last_ping}.
 *
 * <p>A rider's app reports latitude/longitude; everything else is the server's: the time (this service's clock, never
 * the app's), the city (the rider's profile, never the app's), and whether the move is physically possible. A jump of
 * more than 250 m at more than 120 km/h since the last accepted fix is dropped -- dispatch keeps the last good
 * position -- and counted as a strike. The third strike within an hour takes an idle (ONLINE) rider off duty with
 * reason {@link DutyChangeReason#LOCATION_IMPLAUSIBLE}; a rider carrying an order is never demoted, their impossible
 * fixes are only dropped and logged. The account stays active.
 *
 * <p>Owner decisions 2026-10-11, RandomDocuments/RiderGpsAntiSpoofing_2026-10-11.
 */
@Component
@Slf4j
public class RiderLocationGuard {

    static final double MAX_SPEED_KMH = 120.0;
    /** GPS jitter at a 5 s cadence and a cold fix can move a parked rider this far; never a strike. */
    static final double JITTER_METRES = 250.0;
    static final int STRIKES_TO_DEMOTE = 3;
    static final Duration STRIKE_WINDOW = Duration.ofHours(1);
    /** Longer than the liveness window: a fix older than this says nothing about the next one. */
    static final Duration LAST_FIX_TTL = Duration.ofMinutes(10);
    static final Duration CITY_TTL = Duration.ofHours(1);

    static final String LAST_FIX = "driver:last_fix:";
    static final String CITY = "driver:city:";
    static final String STRIKES = "driver:spoof_strikes:";

    private final StringRedisTemplate redis;
    private final IDeliveryExecutiveRepository repository;
    private final RiderDutyDemotion demotion;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public RiderLocationGuard(StringRedisTemplate redis, IDeliveryExecutiveRepository repository,
                              RiderDutyDemotion demotion, MeterRegistry meterRegistry, Clock clock) {
        this.redis = redis;
        this.repository = repository;
        this.demotion = demotion;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /** An accepted position, stamped by this server. */
    public record Fix(String riderId, String cityId, double lat, double lng, long observedAtMs) {
    }

    /**
     * Checks one reported position. Accepted fixes are written to dispatch's index; rejected ones are not.
     *
     * @return the accepted fix, or empty when the position was dropped
     */
    public Optional<Fix> accept(UUID riderId, double lat, double lng) {
        String id = riderId.toString();
        if (!validCoordinates(lat, lng)) {
            meterRegistry.counter("rider.location.rejected", "reason", "invalid").increment();
            log.warn("RIDER_LOCATION_REJECTED driverId={} reason=invalid-coordinates lat={} lng={}", id, lat, lng);
            return Optional.empty();
        }
        String cityId = cityOf(riderId);
        if (cityId == null) {
            meterRegistry.counter("rider.location.rejected", "reason", "off-duty").increment();
            log.warn("RIDER_LOCATION_REJECTED driverId={} reason=off-duty-or-no-profile-city", id);
            return Optional.empty();
        }
        long now = clock.millis();
        String last = redis.opsForValue().get(LAST_FIX + id);
        if (last != null) {
            String[] p = last.split(",");
            double metres = haversineMetres(Double.parseDouble(p[0]), Double.parseDouble(p[1]), lat, lng);
            long elapsedMs = now - Long.parseLong(p[2]);
            double speedKmh = elapsedMs <= 0 ? Double.POSITIVE_INFINITY : metres / (elapsedMs / 1000.0) * 3.6;
            if (metres > JITTER_METRES && speedKmh > MAX_SPEED_KMH) {
                strike(riderId, metres, elapsedMs, speedKmh);
                return Optional.empty();
            }
        }
        return Optional.of(record(id, cityId, lat, lng, now));
    }

    /**
     * A rider coming on duty from OFFLINE starts clean: no previous fix to compare with, no strikes. Going online again
     * while already on duty is an ordinary update and goes through {@link #accept}.
     */
    public Fix startFresh(DeliveryExecutive rider, double lat, double lng) {
        String id = rider.getId().toString();
        if (!validCoordinates(lat, lng) || rider.getCityId() == null) {
            throw new IllegalArgumentException("A valid location and a profile city are required to go online.");
        }
        redis.delete(STRIKES + id);
        redis.opsForValue().set(CITY + id, rider.getCityId(), CITY_TTL);
        return record(id, rider.getCityId(), lat, lng, clock.millis());
    }

    /** Clears the per-rider state when they leave duty, so a later shift starts fresh. */
    public void forget(UUID riderId) {
        forgetKeys(redis, riderId.toString());
    }

    public static void forgetKeys(StringRedisTemplate redis, String riderId) {
        redis.delete(java.util.List.of(LAST_FIX + riderId, CITY + riderId));
    }

    private Fix record(String id, String cityId, double lat, double lng, long now) {
        redis.opsForGeo().add("drivers:geo:" + cityId, new Point(lng, lat), id);
        redis.opsForZSet().add(RiderLiveness.LAST_PING_KEY, id, now);
        redis.opsForValue().set(LAST_FIX + id, lat + "," + lng + "," + now, LAST_FIX_TTL);
        return new Fix(id, cityId, lat, lng, now);
    }

    private void strike(UUID riderId, double metres, long elapsedMs, double speedKmh) {
        String id = riderId.toString();
        String key = STRIKES + id;
        Long strikes = redis.opsForValue().increment(key);
        if (strikes != null && strikes == 1) redis.expire(key, STRIKE_WINDOW);
        meterRegistry.counter("rider.location.rejected", "reason", "implausible").increment();
        log.warn("RIDER_LOCATION_REJECTED driverId={} reason=implausible metres={} seconds={} speedKmh={} strikes={}",
                id, Math.round(metres), elapsedMs / 1000.0, Math.round(speedKmh), strikes);
        if (strikes == null || strikes < STRIKES_TO_DEMOTE) return;
        DeliveryExecutive demoted = demotion.demote(riderId, rider -> true, DutyChangeReason.LOCATION_IMPLAUSIBLE);
        if (demoted != null) {
            redis.delete(key);
            log.warn("RIDER_DEMOTED_LOCATION_IMPLAUSIBLE driverId={} strikes={}", id, strikes);
        } else {
            // Not ONLINE under the lock: carrying an order (never interrupted) or already off duty.
            log.warn("RIDER_LOCATION_REJECTED_NOT_DEMOTED driverId={} strikes={} (not ONLINE; an order in progress is never interrupted)",
                    id, strikes);
        }
    }

    private String cityOf(UUID riderId) {
        String id = riderId.toString();
        String cached = redis.opsForValue().get(CITY + id);
        if (cached != null) return cached;
        String cityId = repository.findById(riderId)
                .filter(rider -> rider.getStatus() != DeliveryExecutiveStatus.OFFLINE)
                .map(DeliveryExecutive::getCityId)
                .orElse(null);
        if (cityId != null) redis.opsForValue().set(CITY + id, cityId, CITY_TTL);
        return cityId;
    }

    public static boolean validCoordinates(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180
                && !(lat == 0 && lng == 0);
    }

    static double haversineMetres(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
