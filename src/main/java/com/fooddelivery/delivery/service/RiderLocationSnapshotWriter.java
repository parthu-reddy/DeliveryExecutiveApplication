package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.RedisKeyConstants;
import com.fooddelivery.common.dto.maps.LiveRiderLocation;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/** Writes only fresh, assigned telemetry; delayed/offline uploads cannot freshen an ETA. */
@lombok.extern.slf4j.Slf4j
public final class RiderLocationSnapshotWriter {
    private RiderLocationSnapshotWriter() { }

    private static final DefaultRedisScript<Long> WRITE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            local previous = redis.call('GET', KEYS[2])
            if previous then
              local ok, decoded = pcall(cjson.decode, previous)
              if ok and tonumber(decoded.observedAtEpochMs) and
                 tonumber(decoded.observedAtEpochMs) > tonumber(ARGV[3]) then return 0 end
            end
            redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[4])
            return 1
            """, Long.class);

    public static void write(StringRedisTemplate redis, ObjectMapper mapper, String driverId,
                             String orderId, String cityId, double lat, double lng,
                             long observedAtMs, long nowMs) {
        if (driverId == null || orderId == null || orderId.isBlank() || cityId == null || cityId.isBlank()) return;
        var location = new LiveRiderLocation(driverId, orderId, cityId, lat, lng, observedAtMs);
        if (!location.isFresh(nowMs)) return;
        try {
            redis.execute(WRITE, List.of(RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER + driverId,
                            LiveRiderLocation.redisKey(orderId)), orderId, mapper.writeValueAsString(location),
                    Long.toString(observedAtMs), Long.toString(observedAtMs + LiveRiderLocation.MAX_AGE_MS - nowMs));
        } catch (Exception error) {
            // ETA is optional; its cache failure must not discard valid rider telemetry.
            log.warn("RIDER_ETA_SNAPSHOT_UNAVAILABLE orderId={} errorType={}", orderId,
                    error.getClass().getSimpleName());
        }
    }
}
