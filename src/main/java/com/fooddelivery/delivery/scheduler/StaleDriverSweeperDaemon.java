package com.fooddelivery.delivery.scheduler;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@lombok.extern.slf4j.Slf4j
public class StaleDriverSweeperDaemon {
private final StringRedisTemplate redisTemplate;
    private final IDeliveryExecutiveRepository deliveryExecutiveRepository;
    private static final String DRIVER_LAST_PING_KEY = "driver_last_ping";
    private static final long STALE_THRESHOLD_MS = 60000; // 60 seconds

    @Scheduled(fixedDelay = 60000)
    public void sweepStaleDrivers() {
        com.fooddelivery.common.lock.RedisLock _redisLock = new com.fooddelivery.common.lock.RedisLock(redisTemplate);
        String _lockToken = java.util.UUID.randomUUID().toString();
        boolean locked = _redisLock.tryAcquire(com.fooddelivery.common.constants.RedisKeyConstants.LOCK_SWEEP_STALE_DRIVERS, _lockToken, java.time.Duration.ofSeconds(50));
        if (!locked) { return; }
        try {    
            log.info("Starting StaleDriverSweeperDaemon sweep...");
            long thresholdTimestamp = System.currentTimeMillis() - STALE_THRESHOLD_MS;
            try {
                // Find all driver IDs whose last ping was older than the threshold
                Set<String> staleDriverIds = redisTemplate.opsForZSet().rangeByScore(DRIVER_LAST_PING_KEY, 0, thresholdTimestamp);
                if (staleDriverIds != null && !staleDriverIds.isEmpty()) {
                    log.info("Found {} stale drivers. Processing offline status...", staleDriverIds.size());
                    processStaleDriversBatch(staleDriverIds);
                } else {
                    log.debug("No stale drivers found in this sweep cycle.");
                }
            } catch (Exception e) {
                log.error("Error during StaleDriverSweeperDaemon execution", e);
            }
        
        } finally {
            _redisLock.release(com.fooddelivery.common.constants.RedisKeyConstants.LOCK_SWEEP_STALE_DRIVERS, _lockToken);
        }}

    /**
     * Batch-processes stale drivers: fetches all entities in a single DB call,
     * updates their status, saves them all, then cleans up Redis.
     */
    private void processStaleDriversBatch(Set<String> staleDriverIdStrings) {
        // Parse valid UUIDs, skip invalid ones
        List<UUID> validUuids = new ArrayList<>();
        List<String> invalidIds = new ArrayList<>();
        for (String idStr : staleDriverIdStrings) {
            try {
                validUuids.add(UUID.fromString(idStr));
            } catch (IllegalArgumentException e) {
                log.error("Invalid UUID format for driverId: {}", idStr);
                invalidIds.add(idStr);
            }
        }
        // Clean up invalid IDs from Redis immediately
        for (String invalidId : invalidIds) {
            // Cannot reliably clean geo or available sets for invalid IDs without cityId
            redisTemplate.opsForZSet().remove(DRIVER_LAST_PING_KEY, invalidId);
        }
        if (validUuids.isEmpty()) return;
        // Batch-fetch all stale driver entities in a single DB call
        List<DeliveryExecutive> drivers = deliveryExecutiveRepository.findAllById(validUuids);
        Map<UUID, DeliveryExecutive> driverMap = drivers.stream().collect(Collectors.toMap(DeliveryExecutive::getId, Function.identity()));
        List<DeliveryExecutive> driversToSave = new ArrayList<>();
        List<String> successfullyUpdatedIds = new ArrayList<>();
        for (UUID driverId : validUuids) {
            DeliveryExecutive driver = driverMap.get(driverId);
            if (driver != null && driver.getStatus() != DeliveryExecutiveStatus.OFFLINE) {
                driver.setStatus(DeliveryExecutiveStatus.OFFLINE);
                driversToSave.add(driver);
                successfullyUpdatedIds.add(driverId.toString());
                log.info("Marked driver {} as OFFLINE due to inactivity", driverId);
            } else if (driver == null) {
                // Driver not in DB but exists in Redis — clean up orphaned Redis entry
                successfullyUpdatedIds.add(driverId.toString());
                log.warn("Driver {} found in Redis but not in database. Cleaning up orphaned entry.", driverId);
            } else {
                // Already OFFLINE — just clean up Redis
                successfullyUpdatedIds.add(driverId.toString());
            }
        }
        // Batch-save all updated entities
        try {
            if (!driversToSave.isEmpty()) {
                deliveryExecutiveRepository.saveAll(driversToSave);
            }
            // Only clean Redis AFTER DB commit succeeds
            for (String driverIdStr : successfullyUpdatedIds) {
                DeliveryExecutive driver = null;
                try {
                    driver = driverMap.get(UUID.fromString(driverIdStr));
                } catch (Exception ignored) {
                    log.debug("Invalid UUID format for driver: {}", driverIdStr);
                }
                
                if (driver != null && driver.getCityId() != null) {
                    redisTemplate.opsForGeo().remove("drivers:geo:" + driver.getCityId(), driverIdStr);
                    redisTemplate.opsForSet().remove("drivers:available:" + driver.getCityId(), driverIdStr);
                }
                
                redisTemplate.opsForZSet().remove(DRIVER_LAST_PING_KEY, driverIdStr);
                redisTemplate.opsForHash().put("drivers:status", driverIdStr, DeliveryExecutiveStatus.OFFLINE.name());
            }
        } catch (Exception dbEx) {
            log.error("DUAL_WRITE_PREVENTION: DB batch update failed for {} drivers. " + "Skipping Redis cleanup. Drivers remain in Redis and will be retried next cycle.", driversToSave.size(), dbEx);
        }
    }

public StaleDriverSweeperDaemon(final StringRedisTemplate redisTemplate, final IDeliveryExecutiveRepository deliveryExecutiveRepository) {
        this.redisTemplate = redisTemplate;
        this.deliveryExecutiveRepository = deliveryExecutiveRepository;
    }
}
