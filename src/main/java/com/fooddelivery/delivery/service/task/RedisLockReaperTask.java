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
            if (keys != null && !keys.isEmpty()) {
                for (String key : keys) {
                    String driverIdStr = key.replace(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER, "");
                    String orderIdStr = redisTemplate.opsForValue().get(key);
                    
                    if (orderIdStr != null) {
                        try {
                            UUID driverId = UUID.fromString(driverIdStr);
                            repository.findById(driverId).ifPresent(executive -> {
                                // If the driver is ONLINE or OFFLINE, they should NOT have an active order lock
                                if (executive.getStatus() == DeliveryExecutiveStatus.ONLINE || 
                                    executive.getStatus() == DeliveryExecutiveStatus.OFFLINE) {
                                    
                                    log.warn("Reaper Task: Found orphaned lock for driver {} (status: {}) on order {}. Removing locks.", 
                                            driverId, executive.getStatus(), orderIdStr);
                                    
                                    redisTemplate.delete(key);
                                    redisTemplate.delete(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderIdStr);
                                }
                            });
                        } catch (Exception e) {
                            log.error("Reaper Task: Error processing key {}", key, e);
                        }
                    }
                }
            }
            
            // Note: the "order:driver:lock:*" could also be orphaned without com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER + "*" 
            // if acceptOrderPing crashed right after lock script but before com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER + "*" was set.
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
            if (lockKeys != null) {
                for (String lockKey : lockKeys) {
                    String orderIdStr = lockKey.replace("order:driver:lock:", "");
                    String driverIdStr = redisTemplate.opsForValue().get(lockKey);
                    if (driverIdStr != null) {
                        try {
                            UUID driverId = UUID.fromString(driverIdStr);
                            repository.findById(driverId).ifPresent(executive -> {
                                if (executive.getStatus() == DeliveryExecutiveStatus.ONLINE || 
                                    executive.getStatus() == DeliveryExecutiveStatus.OFFLINE) {
                                    
                                    log.warn("Reaper Task: Found orphaned order lock for driver {} (status: {}) on order {}. Removing lock.", 
                                            driverId, executive.getStatus(), orderIdStr);
                                    
                                    redisTemplate.delete(lockKey);
                                }
                            });
                        } catch (Exception e) {
                             log.error("Reaper Task: Error processing lockKey {}", lockKey, e);
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("Error during Redis Lock Reaper Task", e);
        } finally {
            redisTemplate.delete("lock:reaper_task_execution");
        }
    }
}
