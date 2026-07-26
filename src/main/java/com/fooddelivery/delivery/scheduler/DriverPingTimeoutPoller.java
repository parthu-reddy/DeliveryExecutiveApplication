package com.fooddelivery.delivery.scheduler;

import com.fooddelivery.delivery.service.OrderAssignmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class DriverPingTimeoutPoller {

    private final StringRedisTemplate redisTemplate;
    private final OrderAssignmentService orderAssignmentService;

    @Scheduled(fixedDelay = 5000)
    public void pollPingTimeouts() {
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(com.fooddelivery.common.constants.RedisKeyConstants.LOCK_POLL_PING_TIMEOUTS, "1", java.time.Duration.ofSeconds(4));
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        
        Set<String> timedOutOrders = redisTemplate.opsForZSet().rangeByScore(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_PING_TIMEOUTS, 0, currentTime);
        
        if (timedOutOrders != null && !timedOutOrders.isEmpty()) {
            for (String orderIdStr : timedOutOrders) {
                try {
                    orderAssignmentService.timeoutOrderPing(UUID.fromString(orderIdStr));
                } catch (Exception e) {
                    log.error("Failed to process ping timeout for order {}", orderIdStr, e);
                }
            }
        }
    }
}
