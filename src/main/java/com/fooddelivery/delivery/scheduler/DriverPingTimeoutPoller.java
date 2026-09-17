package com.fooddelivery.delivery.scheduler;

import com.fooddelivery.delivery.service.OrderAssignmentService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.UUID;

@Component
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
/**
 * <strong>@replication-safe: distributed-lock</strong> -- holds a Redis lock for the poll.
 *
 * <p>Classification recorded 2026-08-27 (Phase 7). Every @Scheduled class in this workspace
 * carries one of these markers; the BOOT-SCHEDULE-CLASSIFIED check fails on a new one that
 * does not. Change the marker only after re-reading what the job actually does.
 */
public class DriverPingTimeoutPoller {
private final StringRedisTemplate redisTemplate;
    private final OrderAssignmentService orderAssignmentService;

    @Scheduled(fixedDelay = 5000)
    public void pollPingTimeouts() {
        com.fooddelivery.common.lock.RedisLock _redisLock = new com.fooddelivery.common.lock.RedisLock(redisTemplate);
        String _lockToken = java.util.UUID.randomUUID().toString();
        boolean locked = _redisLock.tryAcquire(com.fooddelivery.common.constants.RedisKeyConstants.LOCK_POLL_PING_TIMEOUTS, _lockToken, java.time.Duration.ofSeconds(60));
        if (!locked) { return; }
        try {    
            long currentTime = System.currentTimeMillis();
            Set<String> timedOutOrders = redisTemplate.opsForZSet().rangeByScore(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS, 0, currentTime, 0, 50);
            if (timedOutOrders != null && !timedOutOrders.isEmpty()) {
                for (String orderIdStr : timedOutOrders) {
                    try {
                        orderAssignmentService.timeoutOrderPing(UUID.fromString(orderIdStr));
                    } catch (Exception e) {
                        log.error("Failed to process ping timeout for order {}", orderIdStr, e);
                    }
                }
            }
        
        } finally {
            _redisLock.release(com.fooddelivery.common.constants.RedisKeyConstants.LOCK_POLL_PING_TIMEOUTS, _lockToken);
        }}

}
