package com.fooddelivery.delivery.scheduler;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.fooddelivery.delivery.enums.DutyChangeReason;
import com.fooddelivery.delivery.service.duty.RiderDutyNotifier;
import com.fooddelivery.delivery.service.RiderLiveness;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
/**
 * <strong>@replication-safe: distributed-lock</strong> -- holds a Redis lock for the sweep.
 *
 * <p>Classification recorded 2026-08-27 (Phase 7). Every @Scheduled class in this workspace
 * carries one of these markers; the BOOT-SCHEDULE-CLASSIFIED check fails on a new one that
 * does not. Change the marker only after re-reading what the job actually does.
 */
public class StaleDriverSweeperDaemon {
private final StringRedisTemplate redisTemplate;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final IDeliveryExecutiveRepository deliveryExecutiveRepository;
    private final TransactionTemplate transactionTemplate;
    private final RiderDutyNotifier dutyNotifier;
    private static final String DRIVER_LAST_PING_KEY = RiderLiveness.LAST_PING_KEY;
    static final long STALE_THRESHOLD_MS = RiderLiveness.MAX_SIGNAL_AGE_MS;

    @Scheduled(fixedDelay = 60000)
    public void sweepStaleDrivers() {
        com.fooddelivery.common.lock.RedisLock _redisLock = new com.fooddelivery.common.lock.RedisLock(redisTemplate);
        String _lockToken = java.util.UUID.randomUUID().toString();
        boolean locked = _redisLock.tryAcquire(com.fooddelivery.common.constants.RedisKeyConstants.LOCK_SWEEP_STALE_DRIVERS, _lockToken, java.time.Duration.ofSeconds(50));
        if (!locked) { return; }
        try {
          com.fooddelivery.common.lock.LockedWorkTimer.timed(meterRegistry, "staleDriverSweeperDaemon",
                  java.time.Duration.ofSeconds(50), () -> {
            try {
                sweep(System.currentTimeMillis());
            } catch (Exception e) {
                log.error("Error during StaleDriverSweeperDaemon execution", e);
            }
          });
        } finally {
            _redisLock.release(com.fooddelivery.common.constants.RedisKeyConstants.LOCK_SWEEP_STALE_DRIVERS, _lockToken);
        }}

    /**
     * One pass. Driven by the database, not by the ping set.
     *
     * <p>It used to walk {@code driver_last_ping} only, so an ONLINE row with no entry there (a
     * Redis write that failed after commit, a Redis restart) was never examined and stayed ONLINE
     * forever. It also demoted every stale rider that was not already OFFLINE -- including riders
     * carrying an order, whose status then said OFFLINE mid-delivery.
     *
     * <p>Now: every ONLINE rider whose ping is missing or older than the threshold is demoted, one
     * at a time, and told. ON_DELIVERY is a statement about an order, not about signal, and is never
     * touched here; its ping entry is kept so the rider is re-examined once the delivery ends.
     */
    void sweep(long now) {
        long threshold = now - STALE_THRESHOLD_MS;
        Map<String, Double> pings = new HashMap<>();
        Set<ZSetOperations.TypedTuple<String>> entries = redisTemplate.opsForZSet().rangeWithScores(DRIVER_LAST_PING_KEY, 0, -1);
        if (entries != null) {
            for (ZSetOperations.TypedTuple<String> t : entries) {
                if (t.getValue() != null && t.getScore() != null) pings.put(t.getValue(), t.getScore());
            }
        }

        Set<String> online = new HashSet<>();
        int demoted = 0;
        for (DeliveryExecutive rider : deliveryExecutiveRepository.findByStatus(DeliveryExecutiveStatus.ONLINE)) {
            String id = rider.getId().toString();
            online.add(id);
            Double lastPing = pings.get(id);
            if (lastPing == null || lastPing < threshold) {
                try {
                    if (demote(rider.getId(), threshold)) demoted++;
                } catch (Exception e) {
                    // One rider's lock conflict must not stop the rest of the sweep.
                    log.warn("STALE_SWEEP_DEMOTE_FAILED driverId={}; retried next cycle", id, e);
                }
            }
        }

        // Stale entries that belong to nobody on duty. ON_DELIVERY entries stay (see above).
        Set<String> staleNotOnline = new HashSet<>();
        for (Map.Entry<String, Double> e : pings.entrySet()) {
            if (e.getValue() < threshold && !online.contains(e.getKey())) staleNotOnline.add(e.getKey());
        }
        if (!staleNotOnline.isEmpty()) {
            Map<UUID, DeliveryExecutiveStatus> statuses = new HashMap<>();
            List<UUID> ids = staleNotOnline.stream().map(StaleDriverSweeperDaemon::parse).filter(java.util.Objects::nonNull).toList();
            for (DeliveryExecutive d : deliveryExecutiveRepository.findAllById(ids)) statuses.put(d.getId(), d.getStatus());
            for (String id : staleNotOnline) {
                UUID uuid = parse(id);
                if (uuid != null && statuses.get(uuid) == DeliveryExecutiveStatus.ON_DELIVERY) continue;
                redisTemplate.opsForZSet().remove(DRIVER_LAST_PING_KEY, id);
            }
        }
        if (demoted > 0) log.info("StaleDriverSweeperDaemon demoted {} rider(s) with no recent location", demoted);
    }

    /**
     * Demotes one rider if, under the row lock, they are still ONLINE and their ping is still stale.
     * Both are re-read: between the scan and the lock the rider may have pinged, gone offline, or
     * accepted an order.
     */
    private boolean demote(UUID driverId, long threshold) {
        String id = driverId.toString();
        DeliveryExecutive demotedRider = transactionTemplate.execute(status -> {
            DeliveryExecutive rider = deliveryExecutiveRepository.findLockedById(driverId).orElse(null);
            if (rider == null || rider.getStatus() != DeliveryExecutiveStatus.ONLINE) return null;
            Double lastPing = redisTemplate.opsForZSet().score(DRIVER_LAST_PING_KEY, id);
            if (lastPing != null && lastPing >= threshold) return null;
            rider.setStatus(DeliveryExecutiveStatus.OFFLINE);
            return deliveryExecutiveRepository.save(rider);
        });
        if (demotedRider == null) return false;
        // Redis only AFTER the commit.
        if (demotedRider.getCityId() != null) {
            redisTemplate.opsForSet().remove(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVERS_AVAILABLE + demotedRider.getCityId(), id);
            redisTemplate.opsForGeo().remove("drivers:geo:" + demotedRider.getCityId(), id);
        }
        redisTemplate.opsForZSet().remove(DRIVER_LAST_PING_KEY, id);
        redisTemplate.opsForHash().put("drivers:status", id, DeliveryExecutiveStatus.OFFLINE.name());
        dutyNotifier.publish(driverId, DeliveryExecutiveStatus.OFFLINE, DutyChangeReason.LOCATION_LOST);
        log.info("Marked driver {} as OFFLINE: no location for {} s", id, STALE_THRESHOLD_MS / 1000);
        return true;
    }

    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
