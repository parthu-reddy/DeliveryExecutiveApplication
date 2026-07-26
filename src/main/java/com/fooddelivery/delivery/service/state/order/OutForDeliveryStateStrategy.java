package com.fooddelivery.delivery.service.state.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.LogisticsDispatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Slf4j
@Component
public class OutForDeliveryStateStrategy extends AbstractDeliveryOrderState {

    public OutForDeliveryStateStrategy(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, TransactionTemplate transactionTemplate, OutboxEventRepository outboxEventRepository, IDeliveryExecutiveRepository repository, LogisticsDispatchService logisticsDispatchService) {
        super(redisTemplate, objectMapper, transactionTemplate, outboxEventRepository, repository, logisticsDispatchService);
    }

    @Override
    public DeliveryStatus getSupportedStatus() {
        return DeliveryStatus.OUT_FOR_DELIVERY;
    }

    @Override
    protected com.fooddelivery.common.constants.EventType getEventType() {
        return EventType.ORDER_STATUS_UPDATED;
    }

    @Override
    protected void validate(UUID driverId, UUID orderId, String pickupOtp, String deliveryOtp) {
        String restaurantStatus = redisTemplate.opsForValue().get(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_RESTAURANT_STATUS + orderId);
        if (restaurantStatus == null || !(restaurantStatus.equals(com.fooddelivery.common.enums.OrderStatus.READY_FOR_PICKUP.name()))) {
            throw new IllegalArgumentException("Restaurant has not marked the order as ready yet.");
        }

        String payload = redisTemplate.opsForValue().get(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_PAYLOAD + orderId);
        if (payload != null) {
            try {
                JsonNode root = objectMapper.readTree(payload);
                String expectedOtp = root.path("pickupOtp").asText(null);
                if (expectedOtp != null && !expectedOtp.isEmpty() && !expectedOtp.equals(pickupOtp)) {
                    throw new IllegalArgumentException("Invalid Pickup OTP");
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                log.error("Failed to parse dispatch payload for order {}", orderId, e);
            }
        }
    }
}
