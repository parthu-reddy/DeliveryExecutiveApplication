package com.fooddelivery.delivery.service.job;

import com.fooddelivery.common.repository.IIdempotencyKeyRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class IdempotencySweepJob {

    private final IIdempotencyKeyRepository idempotencyKeyRepository;


    @Scheduled(cron = "0 0 * * * *") // Run hourly
    @Transactional
    public void sweepOldIdempotencyKeys() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
            int deletedCount = idempotencyKeyRepository.deleteOlderThan(cutoff);
            log.info("Swept {} old idempotency keys from DeliveryExecutiveApplication (older than {})", deletedCount, cutoff);
        } catch (Exception e) {
            log.error("Failed to sweep old idempotency keys", e);
        }
    }
}
