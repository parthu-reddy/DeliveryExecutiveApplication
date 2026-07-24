package com.fooddelivery.delivery.scheduler;

import com.fooddelivery.delivery.service.DeliveryService;
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
    private final DeliveryService deliveryService;

    @Scheduled(fixedDelay = 5000)
    public void pollPingTimeouts() {
        Boolean locked = redisTemplate.opsForValue().setIfAbsent("lock:pollPingTimeouts", "1", java.time.Duration.ofSeconds(4));
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        
        Set<String> timedOutOrders = redisTemplate.opsForZSet().rangeByScore("order:ping:timeouts", 0, currentTime);
        
        if (timedOutOrders != null && !timedOutOrders.isEmpty()) {
            for (String orderIdStr : timedOutOrders) {
                try {
                    deliveryService.timeoutOrderPing(UUID.fromString(orderIdStr));
                } catch (Exception e) {
                    log.error("Failed to process ping timeout for order {}", orderIdStr, e);
                }
            }
        }
    }
}
