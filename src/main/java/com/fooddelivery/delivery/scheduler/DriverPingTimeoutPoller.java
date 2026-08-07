package com.fooddelivery.delivery.scheduler;

import com.fooddelivery.delivery.service.OrderAssignmentService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.UUID;

@Component
public class DriverPingTimeoutPoller {
    @java.lang.SuppressWarnings("all")
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DriverPingTimeoutPoller.class);
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

    @java.lang.SuppressWarnings("all")
    public DriverPingTimeoutPoller(final StringRedisTemplate redisTemplate, final OrderAssignmentService orderAssignmentService) {
        this.redisTemplate = redisTemplate;
        this.orderAssignmentService = orderAssignmentService;
    }
}
