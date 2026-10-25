package com.fooddelivery.delivery.service.state.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.dto.maps.LiveRiderLocation;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.delivery.entity.OrderAssignment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * An "Arrived" tap is verified only by a fresh live position of this rider on this order, within 150 m of the target
 * copied onto the assignment (DelayPenalties Phase 2, D10). Every other case is unverified, and none of them throws.
 */
class ArrivalVerificationTest {

    private static final Instant NOW = Instant.parse("2026-10-25T09:30:00Z");
    private static final double OUTLET_LAT = 12.971598, OUTLET_LNG = 77.594562, DROP_LAT = 12.935242, DROP_LNG = 77.624400;
    /** About 111 m of latitude per 0.001 degree. */
    private static final double METRES_PER_MILLIDEGREE = 111.2;

    private final UUID orderId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private ValueOperations<String, String> values;
    private ArrivalVerifier verifier;
    private OrderAssignment assignment;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        verifier = new ArrivalVerifier(redis, mapper, Clock.fixed(NOW, ZoneOffset.UTC), ArrivalVerifier.DEFAULT_RADIUS_METRES);
        assignment = OrderAssignment.builder().orderId(orderId).driverId(driverId).state(OrderAssignment.State.ASSIGNED)
                .pickupLat(OUTLET_LAT).pickupLng(OUTLET_LNG).dropLat(DROP_LAT).dropLng(DROP_LNG).build();
    }

    private void fix(String driver, String order, double lat, double lng, long ageMs) throws Exception {
        when(values.get(LiveRiderLocation.redisKey(orderId.toString()))).thenReturn(mapper.writeValueAsString(
                new LiveRiderLocation(driver, order, "BLR", lat, lng, NOW.toEpochMilli() - ageMs)));
    }

    @Test
    void insideTheRadiusIsVerifiedWithItsDistance() throws Exception {
        fix(driverId.toString(), orderId.toString(), OUTLET_LAT + 0.001, OUTLET_LNG, 5_000);
        ArrivalVerifier.Result result = verifier.check(assignment, driverId, DeliveryStatus.AT_RESTAURANT);
        assertTrue(result.verified());
        assertEquals(METRES_PER_MILLIDEGREE, result.distanceMetres(), 1.0);
    }

    @Test
    void insideTheRadiusOfTheDoorIsVerifiedForAtDrop() throws Exception {
        fix(driverId.toString(), orderId.toString(), DROP_LAT, DROP_LNG + 0.0005, 1_000);
        assertTrue(verifier.check(assignment, driverId, DeliveryStatus.AT_DROP).verified());
    }

    @Test
    void outsideTheRadiusIsUnverifiedButKeepsTheDistance() throws Exception {
        fix(driverId.toString(), orderId.toString(), DROP_LAT + 0.003, DROP_LNG, 5_000);
        ArrivalVerifier.Result result = verifier.check(assignment, driverId, DeliveryStatus.AT_DROP);
        assertFalse(result.verified());
        assertEquals(3 * METRES_PER_MILLIDEGREE, result.distanceMetres(), 2.0, "D16 needs the distance of an unverified tap");
    }

    @Test
    void staleFixOlderThanThirtySecondsIsUnverifiedWithNoDistance() throws Exception {
        fix(driverId.toString(), orderId.toString(), OUTLET_LAT, OUTLET_LNG, 31_000);
        ArrivalVerifier.Result result = verifier.check(assignment, driverId, DeliveryStatus.AT_RESTAURANT);
        assertFalse(result.verified());
        assertNull(result.distanceMetres());
        assertEquals("STALE_FIX", result.reason());
    }

    @Test
    void missingFixIsUnverified() {
        ArrivalVerifier.Result result = verifier.check(assignment, driverId, DeliveryStatus.AT_RESTAURANT);
        assertFalse(result.verified());
        assertNull(result.distanceMetres());
        assertEquals("NO_FIX", result.reason());
    }

    @Test
    void missingFixBecauseRedisIsDownIsUnverifiedAndDoesNotThrow() {
        when(values.get(anyString())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        assertFalse(verifier.check(assignment, driverId, DeliveryStatus.AT_DROP).verified());
    }

    @Test
    void foreignOrderFixIsIgnored() throws Exception {
        fix(driverId.toString(), UUID.randomUUID().toString(), OUTLET_LAT, OUTLET_LNG, 1_000);
        ArrivalVerifier.Result result = verifier.check(assignment, driverId, DeliveryStatus.AT_RESTAURANT);
        assertFalse(result.verified());
        assertEquals("FOREIGN_FIX", result.reason());
    }

    @Test
    void foreignOrderFixOfAnotherRiderIsIgnored() throws Exception {
        fix(UUID.randomUUID().toString(), orderId.toString(), OUTLET_LAT, OUTLET_LNG, 1_000);
        assertFalse(verifier.check(assignment, driverId, DeliveryStatus.AT_RESTAURANT).verified());
    }

    @Test
    void missingTargetOnTheAssignmentIsUnverified() throws Exception {
        assignment.setPickupLat(null);
        fix(driverId.toString(), orderId.toString(), OUTLET_LAT, OUTLET_LNG, 1_000);
        assertEquals("NO_TARGET", verifier.check(assignment, driverId, DeliveryStatus.AT_RESTAURANT).reason());
    }

    @Test
    void aStepThatIsNotAnArrivalIsNotChecked() {
        assertNull(verifier.check(assignment, driverId, DeliveryStatus.OUT_FOR_DELIVERY));
        verifyNoInteractions(values);
    }
}
