package com.fooddelivery.delivery.service.state.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.AppConstants;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.common.enums.OrderStatus;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.LogisticsDispatchService;
import com.fooddelivery.delivery.service.state.DeliveryExecutiveState;
import com.fooddelivery.delivery.service.state.DeliveryExecutiveStateFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
public class DeliveryFailedStateStrategy extends AbstractDeliveryOrderState {

    public DeliveryFailedStateStrategy(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, TransactionTemplate transactionTemplate, OutboxEventRepository outboxEventRepository, IDeliveryExecutiveRepository repository, LogisticsDispatchService logisticsDispatchService) {
        super(redisTemplate, objectMapper, transactionTemplate, outboxEventRepository, repository, logisticsDispatchService);
    }

    @Override
    public OrderStatus getSupportedStatus() {
        return OrderStatus.DELIVERY_FAILED;
    }

    @Override
    protected com.fooddelivery.common.constants.EventType getEventType() {
        return EventType.DELIVERY_FAILED;
    }

    @Override
    protected void updateExecutiveState(UUID driverId, Boolean goOfflineAfter) {
        DeliveryExecutive executive = repository.findById(driverId)
                .orElseThrow(() -> new IllegalArgumentException("Driver not found"));
        DeliveryExecutiveState state = DeliveryExecutiveStateFactory.getState(executive.getStatus());
        state.completeDelivery(executive);
        
        if (Boolean.TRUE.equals(goOfflineAfter)) {
            log.info("Driver {} opted to go offline after delivery failed", driverId);
            executive.setStatus(com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.OFFLINE);
        }
        
        executive.setUpdatedAt(LocalDateTime.now());
        repository.save(executive);
    }

    @Override
    protected void postProcess(UUID driverId, UUID orderId, Boolean goOfflineAfter) {
        logisticsDispatchService.releaseDriverLock(driverId.toString());
        redisTemplate.delete("order:driver:lock:" + orderId);
        redisTemplate.delete("order:ping:pending:" + orderId);
        redisTemplate.opsForZSet().remove("order:ping:timeouts", orderId.toString());
        redisTemplate.opsForValue().set("order:dispatch:lock:" + orderId, getSupportedStatus().name(), Duration.ofHours(24));

        if (!Boolean.TRUE.equals(goOfflineAfter)) {
            try {
                String key = "drivers:available:" + AppConstants.DEFAULT_CITY_ID;
                redisTemplate.opsForSet().add(key, driverId.toString());
            } catch (Exception e) {
                log.error("Failed to add driver {} back to Redis pool", driverId, e);
            }
        } else {
            log.info("Driver {} opted to go offline, skipping addition to available pool", driverId);
        }
    }
}
