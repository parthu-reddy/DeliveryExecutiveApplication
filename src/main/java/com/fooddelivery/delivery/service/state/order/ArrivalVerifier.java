package com.fooddelivery.delivery.service.state.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.dto.maps.LiveRiderLocation;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.delivery.entity.OrderAssignment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.util.UUID;

/**
 * Was the rider really there when they tapped "Arrived"? (DelayPenalties Phase 2, D10, D16.)
 *
 * <p>An arrival starts the clock on what the restaurant (R2) or the customer (R5) owes the rider, so it is the one input a
 * party could fake to fine somebody else. The check reads the rider's live position for this order ({@link LiveRiderLocation},
 * written only for the rider's current order, at most 30 s old) and measures it against the target copied onto the
 * assignment at dispatch: the outlet for AT_RESTAURANT, the delivery pin for AT_DROP.
 *
 * <p>It never refuses the tap. GPS drift, a dead phone or Redis being down must not stop a delivery; the step is recorded
 * with {@code verified=false} and the arrival-based rules treat it as not measurable. Fixes reach Redis only through
 * {@code RiderLocationGuard}, which already rejects spoofed jumps, so a verified fix is a guarded one.
 */
@lombok.extern.slf4j.Slf4j
public class ArrivalVerifier {

    /** D10: the same distance LiveDeliveryEtaService treats as "the rider has moved". */
    public static final double DEFAULT_RADIUS_METRES = 150;

    /**
     * The outcome. {@code distanceMetres} is null when there was no fresh position for this rider on this order; an
     * unverified tap with a distance was simply too far away.
     */
    public record Result(boolean verified, Integer distanceMetres, String reason) {
        static Result unverified(String reason) {
            return new Result(false, null, reason);
        }
    }

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final double radiusMetres;

    public ArrivalVerifier(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, Clock clock, double radiusMetres) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.radiusMetres = radiusMetres;
    }

    /** True for the two steps that are arrivals. */
    public static boolean isArrival(DeliveryStatus step) {
        return step == DeliveryStatus.AT_RESTAURANT || step == DeliveryStatus.AT_DROP;
    }

    /** Null for a step that is not an arrival. Never throws. */
    public Result check(OrderAssignment assignment, UUID driverId, DeliveryStatus step) {
        if (!isArrival(step)) {
            return null;
        }
        Double targetLat = step == DeliveryStatus.AT_RESTAURANT ? assignment.getPickupLat() : assignment.getDropLat();
        Double targetLng = step == DeliveryStatus.AT_RESTAURANT ? assignment.getPickupLng() : assignment.getDropLng();
        Result result = measure(assignment.getOrderId(), driverId, targetLat, targetLng);
        log.info("ARRIVAL_CHECKED orderId={} driverId={} step={} verified={} distanceMetres={} reason={}",
                assignment.getOrderId(), driverId, step, result.verified(), result.distanceMetres(), result.reason());
        return result;
    }

    private Result measure(UUID orderId, UUID driverId, Double targetLat, Double targetLng) {
        LiveRiderLocation fix;
        try {
            String json = redisTemplate.opsForValue().get(LiveRiderLocation.redisKey(orderId.toString()));
            fix = json == null ? null : objectMapper.readValue(json, LiveRiderLocation.class);
        } catch (Exception unavailable) {
            return Result.unverified("NO_FIX_READ_FAILED");
        }
        if (fix == null) {
            return Result.unverified("NO_FIX");
        }
        // The key is per order, but a value written for another rider (a reassignment race) says nothing about this one.
        if (!orderId.toString().equals(fix.orderId()) || !driverId.toString().equals(fix.driverId())) {
            return Result.unverified("FOREIGN_FIX");
        }
        if (!fix.isFresh(clock.millis())) {
            return Result.unverified("STALE_FIX");
        }
        if (targetLat == null || targetLng == null) {
            return Result.unverified("NO_TARGET");
        }
        double metres = haversineMetres(fix.latitude(), fix.longitude(), targetLat, targetLng);
        int distanceMetres = (int) Math.round(metres);
        return new Result(metres <= radiusMetres, distanceMetres, metres <= radiusMetres ? "WITHIN_RADIUS" : "OUTSIDE_RADIUS");
    }

    /** Great-circle distance, metres. */
    static double haversineMetres(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
