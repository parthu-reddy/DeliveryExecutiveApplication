package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.common.enums.DeliveryStatus;
import org.springframework.stereotype.Service;
import java.util.UUID;

import io.micrometer.observation.annotation.Observed;

@Service
@lombok.extern.slf4j.Slf4j
@Observed(name = "delivery.order.execution")
public class OrderExecutionService {
private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventHelper outboxEventHelper;
    private final IDeliveryExecutiveRepository repository;
    private final LogisticsDispatchService logisticsDispatchService;
    private final com.fooddelivery.delivery.service.state.order.DeliveryOrderStateFactory deliveryOrderStateFactory;
    private final ObjectMapper objectMapper;

    public void abortOrder(UUID driverId, UUID orderId) {
        String currentLock = redisTemplate.opsForValue().get(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId);
        if (currentLock == null || !currentLock.equals(driverId.toString())) {
            throw new IllegalArgumentException("Driver is not assigned to this order or order lock missing");
        }
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                // 1. DB transaction: mark driver available and save outbox event atomically
                transactionTemplate.executeWithoutResult(status -> {
                    DeliveryExecutive executive = repository.findById(driverId).orElseThrow(() -> new IllegalArgumentException("Driver not found"));
                    executive.setStatus(com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.ONLINE);
                    repository.save(executive);
                    redisTemplate.opsForHash().put("drivers:status", driverId.toString(), executive.getStatus().name());
                    com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent = outboxEventHelper.createOutboxEvent(com.fooddelivery.common.constants.AggregateType.ORDER, orderId.toString(), com.fooddelivery.common.constants.EventType.ORDER_DRIVER_REJECTED, java.util.Map.of("orderId", orderId.toString(), "driverId", driverId.toString()));
                    outboxEventRepository.save(outboxEvent);
                });
                break; // exit loop on success
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                if (i == maxRetries - 1) {
                    log.error("Optimistic locking failure while aborting order for driver {}, order {}", driverId, orderId, e);
                    throw new RuntimeException("Concurrent update conflict. Please try again.", e);
                }
                log.warn("Optimistic locking failure for driver {}. Retrying {}/{}", driverId, i + 1, maxRetries);
                try {
                    Thread.sleep(100 + (long) (Math.random() * 100)); // basic jitter
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        // 2. Redis ops AFTER DB commit — if these fail, the outbox event still fires correctly
        redisTemplate.delete(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId);
        redisTemplate.delete(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER + driverId);
        redisTemplate.opsForHash().increment(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_REJECTED_DRIVERS + orderId, driverId.toString(), 1);
        redisTemplate.expire(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_REJECTED_DRIVERS + orderId, java.time.Duration.ofHours(2));
        // Add back to pool
        try {
            String cityId = repository.findById(driverId).map(com.fooddelivery.delivery.entity.DeliveryExecutive::getCityId).orElse(null);
            if (cityId != null) {
                String key = "drivers:available:" + cityId;
                redisTemplate.opsForSet().add(key, driverId.toString());
            }
        } catch (Exception e) {
            log.error("Failed to add driver {} to Redis pool", driverId, e);
        }
        try {
            logisticsDispatchService.releaseDriverLock(driverId.toString());
        } catch (Exception e) {
            log.error("Failed to release driver lock for driver {} on abort, will be retried by availability poller", driverId, e);
        }
        // 3. Trigger dispatch loop again to find a new driver
        log.info("Driver {} aborted order {}. Re-triggering candidate search...", driverId, orderId);
    }

    public void updateOrderStatus(UUID driverId, UUID orderId, DeliveryStatus status, String pickupOtp, String deliveryOtp, Boolean goOfflineAfter) {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                com.fooddelivery.delivery.service.state.order.DeliveryOrderStateStrategy strategy = deliveryOrderStateFactory.getStrategy(status);
                strategy.handleStatusUpdate(driverId, orderId, status, pickupOtp, deliveryOtp, goOfflineAfter);
                return;
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                if (i == maxRetries - 1) {
                    log.error("Optimistic locking failure while updating order status for driver {}, order {}", driverId, orderId, e);
                    throw new RuntimeException("Concurrent update conflict. Please try again.", e);
                }
                log.warn("Optimistic locking failure for driver {}. Retrying {}/{}", driverId, i + 1, maxRetries);
                try {
                    Thread.sleep(100 + (long) (Math.random() * 100)); // basic jitter
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

public OrderExecutionService(final org.springframework.data.redis.core.StringRedisTemplate redisTemplate, final org.springframework.transaction.support.TransactionTemplate transactionTemplate, final OutboxEventRepository outboxEventRepository, final OutboxEventHelper outboxEventHelper, final IDeliveryExecutiveRepository repository, final LogisticsDispatchService logisticsDispatchService, final com.fooddelivery.delivery.service.state.order.DeliveryOrderStateFactory deliveryOrderStateFactory, final ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.transactionTemplate = transactionTemplate;
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventHelper = outboxEventHelper;
        this.repository = repository;
        this.logisticsDispatchService = logisticsDispatchService;
        this.deliveryOrderStateFactory = deliveryOrderStateFactory;
        this.objectMapper = objectMapper;
    }
}
