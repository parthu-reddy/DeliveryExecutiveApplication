package com.fooddelivery.delivery.service.task;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisLockReaperTask {

    private final StringRedisTemplate redisTemplate;
    private final IDeliveryExecutiveRepository repository;

    /**
     * Periodically scan and clean up orphaned Redis locks for drivers.
     * Runs every 2 minutes.
     */
    @Scheduled(fixedRate = 120000)
    public void reapOrphanedLocks() {
        Boolean lockAcquired = redisTemplate.opsForValue().setIfAbsent(
            "lock:reaper_task_execution", 
            "LOCKED", 
            java.time.Duration.ofMinutes(1)
        );
        
        if (!Boolean.TRUE.equals(lockAcquired)) {
            log.debug("Another instance is already running the Reaper Task. Skipping.");
            return;
        }

        log.info("Starting Redis Lock Reaper Task to clean up orphaned locks...");
        try {
            // Find all active order keys using SCAN (non-blocking) instead of KEYS
            Set<String> keys = new java.util.HashSet<>();
            try (var cursor = redisTemplate.getConnectionFactory().getConnection().scan(
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER + "*")
                            .count(100)
                            .build())) {
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
            try (var cursor = redisTemplate.getConnectionFactory().getConnection().scan(
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match("order:driver:lock:*")
                            .count(100)
                            .build())) {
                while (cursor.hasNext()) {
                    lockKeys.add(new String(cursor.next()));
                }
            }
            
            java.util.Map<String, String> lockKeyToDriverIdStr = new java.util.HashMap<>();
            if (lockKeys != null) {
                for (String lockKey : lockKeys) {
                    String driverIdStr = redisTemplate.opsForValue().get(lockKey);
                    if (driverIdStr != null) {
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
                    
                    if (executive != null && (executive.getStatus() == DeliveryExecutiveStatus.ONLINE || 
                                              executive.getStatus() == DeliveryExecutiveStatus.OFFLINE)) {
                        log.warn("Reaper Task: Found orphaned lock for driver {} (status: {}) on order {}. Removing locks.", 
                                driverId, executive.getStatus(), orderIdStr);
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
                    
                    if (executive != null && (executive.getStatus() == DeliveryExecutiveStatus.ONLINE || 
                                              executive.getStatus() == DeliveryExecutiveStatus.OFFLINE)) {
                        log.warn("Reaper Task: Found orphaned order lock for driver {} (status: {}) on order {}. Removing lock.", 
                                driverId, executive.getStatus(), orderIdStr);
                        redisTemplate.delete(lockKey);
                    }
                } catch (Exception e) {
                     log.error("Reaper Task: Error processing lockKey {}", lockKey, e);
                }
            }
            
        } catch (Exception e) {
            log.error("Error during Redis Lock Reaper Task", e);
        } finally {
            redisTemplate.delete("lock:reaper_task_execution");
        }
    }
}
