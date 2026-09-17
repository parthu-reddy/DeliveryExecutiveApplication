package com.fooddelivery.delivery.service.task;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.UUID;

@Component
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
/**
 * <strong>@replication-safe: distributed-lock</strong> -- holds a Redis lock while reaping expired locks.
 *
 * <p>Classification recorded 2026-08-27 (Phase 7). Every @Scheduled class in this workspace
 * carries one of these markers; the BOOT-SCHEDULE-CLASSIFIED check fails on a new one that
 * does not. Change the marker only after re-reading what the job actually does.
 */
public class RedisLockReaperTask {
private final StringRedisTemplate redisTemplate;
    private final IDeliveryExecutiveRepository repository;

    /**
     * Periodically scan and clean up orphaned Redis locks for drivers.
     * Runs every 2 minutes.
     */
    @Scheduled(fixedDelay = 120000)
    public void reapOrphanedLocks() {
        com.fooddelivery.common.lock.RedisLock _redisLock = new com.fooddelivery.common.lock.RedisLock(redisTemplate);
        String _lockToken = java.util.UUID.randomUUID().toString();
        // 5 minutes, not 1. This job does two full keyspace SCANs plus a batched DB fetch, so its
        // runtime grows with the keyspace and a 1-minute TTL was a guess that overruns on a busy
        // instance. A lock TTL is a crash-recovery bound -- how long a dead holder blocks others --
        // not a scheduling interval; the happy path releases in the finally below. Overrunning is
        // now merely wasteful rather than destructive, because that release is token-checked.
        boolean lockAcquired = _redisLock.tryAcquire(com.fooddelivery.common.constants.RedisKeyConstants.LOCK_REAPER_TASK, _lockToken, java.time.Duration.ofMinutes(5));
        if (!Boolean.TRUE.equals(lockAcquired)) {
            log.debug("Another instance is already running the Reaper Task. Skipping.");
            return;
        }
        log.info("Starting Redis Lock Reaper Task to clean up orphaned locks...");
        try {
            // Find all active order keys using SCAN (non-blocking) instead of KEYS
            Set<String> keys = new java.util.HashSet<>();
            try (var cursor = redisTemplate.getConnectionFactory().getConnection().scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER + "*").count(100).build())) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next()));
                }
            }
            // Parse first set of keys
            java.util.Map<String, String> keyToOrderId = new java.util.HashMap<>();
            java.util.Set<UUID> driverIdsToFetch = new java.util.HashSet<>();
            if (keys != null && !keys.isEmpty()) {
                for (String key : keys) {
                    String driverIdStr = key.replace(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER, "");
                    String orderIdStr = redisTemplate.opsForValue().get(key);
                    if (orderIdStr != null) {
                        try {
                            driverIdsToFetch.add(UUID.fromString(driverIdStr));
                            keyToOrderId.put(key, orderIdStr);
                        } catch (Exception e) {
                            log.error("Reaper Task: Error parsing UUID from key {}", key, e);
                        }
                    }
                }
            }
            // Note: the "order:driver:lock:*" could also be orphaned without PREFIX_DRIVER_ACTIVE_ORDER
            Set<String> lockKeys = new java.util.HashSet<>();
            try (var cursor = redisTemplate.getConnectionFactory().getConnection().scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match("order:driver:lock:*").count(100).build())) {
                while (cursor.hasNext()) {
                    lockKeys.add(new String(cursor.next()));
                }
            }
            java.util.Map<String, String> lockKeyToDriverIdStr = new java.util.HashMap<>();
            if (lockKeys != null) {
                for (String lockKey : lockKeys) {
                    String driverIdStr = redisTemplate.opsForValue().get(lockKey);
                    if (driverIdStr != null) {
                        if ("CANCELLED".equals(driverIdStr)) {
                            continue;
                        }
                        try {
                            driverIdsToFetch.add(UUID.fromString(driverIdStr));
                            lockKeyToDriverIdStr.put(lockKey, driverIdStr);
                        } catch (Exception e) {
                            log.error("Reaper Task: Error parsing UUID from lockKey {}", lockKey, e);
                        }
                    }
                }
            }
            // Batch fetch all drivers at once
            java.util.Map<UUID, DeliveryExecutive> driverMap = new java.util.HashMap<>();
            if (!driverIdsToFetch.isEmpty()) {
                repository.findAllById(driverIdsToFetch).forEach(d -> driverMap.put(d.getId(), d));
            }
            // Process first set of keys
            for (java.util.Map.Entry<String, String> entry : keyToOrderId.entrySet()) {
                String key = entry.getKey();
                String orderIdStr = entry.getValue();
                String driverIdStr = key.replace(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER, "");
                try {
                    UUID driverId = UUID.fromString(driverIdStr);
                    DeliveryExecutive executive = driverMap.get(driverId);
                    if (executive != null && (executive.getStatus() == DeliveryExecutiveStatus.ONLINE || executive.getStatus() == DeliveryExecutiveStatus.OFFLINE)) {
                        log.warn("Reaper Task: Found orphaned lock for driver {} (status: {}) on order {}. Removing locks.", driverId, executive.getStatus(), orderIdStr);
                        redisTemplate.delete(key);
                        redisTemplate.delete(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderIdStr);
                    }
                } catch (Exception e) {
                    log.error("Reaper Task: Error processing key {}", key, e);
                }
            }
            // Process second set of keys
            for (java.util.Map.Entry<String, String> entry : lockKeyToDriverIdStr.entrySet()) {
                String lockKey = entry.getKey();
                String driverIdStr = entry.getValue();
                String orderIdStr = lockKey.replace("order:driver:lock:", "");
                try {
                    UUID driverId = UUID.fromString(driverIdStr);
                    DeliveryExecutive executive = driverMap.get(driverId);
                    if (executive != null && (executive.getStatus() == DeliveryExecutiveStatus.ONLINE || executive.getStatus() == DeliveryExecutiveStatus.OFFLINE)) {
                        log.warn("Reaper Task: Found orphaned order lock for driver {} (status: {}) on order {}. Removing lock.", driverId, executive.getStatus(), orderIdStr);
                        redisTemplate.delete(lockKey);
                    }
                } catch (Exception e) {
                    log.error("Reaper Task: Error processing lockKey {}", lockKey, e);
                }
            }
        } catch (Exception e) {
            log.error("Error during Redis Lock Reaper Task", e);
        } finally {
            _redisLock.release(com.fooddelivery.common.constants.RedisKeyConstants.LOCK_REAPER_TASK, _lockToken);
        }
    }

}
