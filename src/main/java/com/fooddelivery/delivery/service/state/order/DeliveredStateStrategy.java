package com.fooddelivery.delivery.service.state.order;

import com.fasterxml.jackson.databind.JsonNode;
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
public class DeliveredStateStrategy extends AbstractDeliveryOrderState {

    public DeliveredStateStrategy(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, TransactionTemplate transactionTemplate, OutboxEventRepository outboxEventRepository, IDeliveryExecutiveRepository repository, LogisticsDispatchService logisticsDispatchService) {
        super(redisTemplate, objectMapper, transactionTemplate, outboxEventRepository, repository, logisticsDispatchService);
    }

    @Override
    public OrderStatus getSupportedStatus() {
        return OrderStatus.DELIVERED;
    }

    @Override
    protected com.fooddelivery.common.constants.EventType getEventType() {
        return EventType.ORDER_DELIVERED;
    }

    @Override
    protected void validate(UUID driverId, UUID orderId, String pickupOtp, String deliveryOtp) {
        String payload = redisTemplate.opsForValue().get("order:dispatchPayload:" + orderId);
        if (payload != null) {
            try {
                JsonNode root = objectMapper.readTree(payload);
                String expectedOtp = root.path("deliveryOtp").asText(null);
                if (expectedOtp != null && !expectedOtp.isEmpty() && !expectedOtp.equals(deliveryOtp)) {
                    throw new IllegalArgumentException("Invalid Delivery OTP");
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                log.error("Failed to parse dispatch payload for order {}", orderId, e);
            }
        }
    }

    @Override
    protected void updateExecutiveState(UUID driverId) {
        DeliveryExecutive executive = repository.findById(driverId)
                .orElseThrow(() -> new IllegalArgumentException("Driver not found"));
        DeliveryExecutiveState state = DeliveryExecutiveStateFactory.getState(executive.getStatus());
        state.completeDelivery(executive);
        executive.setUpdatedAt(LocalDateTime.now());
        repository.save(executive);
    }

    @Override
    protected void postProcess(UUID driverId, UUID orderId) {
        logisticsDispatchService.releaseDriverLock(driverId.toString());
        redisTemplate.delete("order:driver:lock:" + orderId);
        redisTemplate.delete("order:ping:pending:" + orderId);
        redisTemplate.opsForZSet().remove("order:ping:timeouts", orderId.toString());
        redisTemplate.opsForValue().set("order:dispatch:lock:" + orderId, getSupportedStatus().name(), Duration.ofHours(24));

        try {
            String key = "drivers:available:" + AppConstants.DEFAULT_CITY_ID;
            redisTemplate.opsForSet().add(key, driverId.toString());
        } catch (Exception e) {
            log.error("Failed to add driver {} back to Redis pool", driverId, e);
        }
    }
}
