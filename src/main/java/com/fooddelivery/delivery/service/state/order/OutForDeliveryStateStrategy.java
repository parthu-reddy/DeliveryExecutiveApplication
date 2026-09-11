package com.fooddelivery.delivery.service.state.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.delivery.entity.OrderAssignment;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.LogisticsDispatchService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

@Component
@lombok.extern.slf4j.Slf4j
public class OutForDeliveryStateStrategy extends AbstractDeliveryOrderState {
    public OutForDeliveryStateStrategy(org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
                 com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                 org.springframework.transaction.support.TransactionTemplate transactionTemplate,
                 com.fooddelivery.common.outbox.repository.OutboxEventRepository outboxEventRepository,
                 com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository repository,
                 com.fooddelivery.delivery.service.LogisticsDispatchService logisticsDispatchService,
                 com.fooddelivery.delivery.repository.OrderAssignmentRepository assignmentRepository) {
        super(redisTemplate, objectMapper, transactionTemplate, outboxEventRepository, repository, logisticsDispatchService, assignmentRepository);
    }


    @Override
    public DeliveryStatus getSupportedStatus() {
        return DeliveryStatus.OUT_FOR_DELIVERY;
    }

    @Override
    protected com.fooddelivery.common.constants.EventType getEventType() {
        return EventType.ORDER_STATUS_UPDATED;
    }

    /**
     * The restaurant must have marked the order ready, and the pickup OTP must match the
     * assignment. The readiness flag stays in Redis -- it is a live status, not an authorization,
     * and a missing one already denies here.
     */
    @Override
    protected void validate(OrderAssignment assignment, String pickupOtp, String deliveryOtp,
                            java.math.BigDecimal cashCollectedAmount) {
        String restaurantStatus = redisTemplate.opsForValue()
                .get(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_RESTAURANT_STATUS + assignment.getOrderId());
        if (!com.fooddelivery.common.enums.OrderStatus.READY_FOR_PICKUP.name().equals(restaurantStatus)) {
            throw new IllegalArgumentException("Restaurant has not marked the order as ready yet.");
        }
        requireOtp(assignment.getPickupOtp(), pickupOtp, "pickup");
    }
}
