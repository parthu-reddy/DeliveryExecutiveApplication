package com.fooddelivery.delivery.scheduler;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class StaleDriverSweeperDaemon {

    private final StringRedisTemplate redisTemplate;
    private final IDeliveryExecutiveRepository deliveryExecutiveRepository;

    private static final String DRIVER_LOCATION_KEY = "driver_locations";
    private static final String DRIVER_LAST_PING_KEY = "driver_last_ping";
    private static final long STALE_THRESHOLD_MS = 60_000; // 60 seconds

    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void sweepStaleDrivers() {
        log.info("Starting StaleDriverSweeperDaemon sweep...");
        long thresholdTimestamp = System.currentTimeMillis() - STALE_THRESHOLD_MS;

        try {
            // Find all driver IDs whose last ping was older than the threshold
            Set<String> staleDriverIds = redisTemplate.opsForZSet().rangeByScore(DRIVER_LAST_PING_KEY, 0, thresholdTimestamp);

            if (staleDriverIds != null && !staleDriverIds.isEmpty()) {
                log.info("Found {} stale drivers. Processing offline status...", staleDriverIds.size());

                for (String driverIdStr : staleDriverIds) {
                    processStaleDriver(driverIdStr);
                }
            } else {
                log.debug("No stale drivers found in this sweep cycle.");
            }
        } catch (Exception e) {
            log.error("Error during StaleDriverSweeperDaemon execution", e);
        }
    }

    private void processStaleDriver(String driverIdStr) {
        try {
            // 1. Remove from the Redis geospatial index
            redisTemplate.opsForGeo().remove(DRIVER_LOCATION_KEY, driverIdStr);

            // 2. Remove from the Redis ZSET
            redisTemplate.opsForZSet().remove(DRIVER_LAST_PING_KEY, driverIdStr);

            // 3. Update the database status to OFFLINE
            UUID driverId = UUID.fromString(driverIdStr);
            deliveryExecutiveRepository.findById(driverId).ifPresent(driver -> {
                if (driver.getStatus() != DeliveryExecutiveStatus.OFFLINE) {
                    driver.setStatus(DeliveryExecutiveStatus.OFFLINE);
                    deliveryExecutiveRepository.save(driver);
                    log.info("Marked driver {} as OFFLINE due to inactivity", driverIdStr);
                }
            });
        } catch (IllegalArgumentException e) {
            log.error("Invalid UUID format for driverId: {}", driverIdStr, e);
            // Clean up invalid ID from Redis to prevent infinite loop of errors
            redisTemplate.opsForGeo().remove(DRIVER_LOCATION_KEY, driverIdStr);
            redisTemplate.opsForZSet().remove(DRIVER_LAST_PING_KEY, driverIdStr);
        } catch (Exception e) {
            log.error("Failed to process stale driver: {}", driverIdStr, e);
        }
    }
}
