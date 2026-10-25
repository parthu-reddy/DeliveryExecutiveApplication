package com.fooddelivery.delivery.service.state.order;

import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.delivery.entity.OrderAssignment;
import org.springframework.stereotype.Component;

/**
 * The rider tapped "I've arrived" at the customer's door (DelayPenalties Phase 2). Geo-checked like the outlet arrival and
 * published as ORDER_AT_DROP. Optional: the delivery OTP is accepted straight after pickup as well (D22), and a rider who
 * skips this step only gives up their waiting-at-the-door claim.
 */
@Component
@lombok.extern.slf4j.Slf4j
public class AtDropStateStrategy extends AbstractDeliveryOrderState {
    public AtDropStateStrategy(org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
                 com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                 org.springframework.transaction.support.TransactionTemplate transactionTemplate,
                 com.fooddelivery.common.outbox.repository.OutboxEventRepository outboxEventRepository,
                 com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository repository,
                 com.fooddelivery.delivery.service.LogisticsDispatchService logisticsDispatchService,
                 com.fooddelivery.delivery.repository.OrderAssignmentRepository assignmentRepository,
                 java.time.Clock clock) {
        super(redisTemplate, objectMapper, transactionTemplate, outboxEventRepository, repository, logisticsDispatchService, assignmentRepository, clock);
    }

    @Override
    public DeliveryStatus getSupportedStatus() {
        return DeliveryStatus.AT_DROP;
    }

    @Override
    public EventType getEventType() {
        return EventType.ORDER_AT_DROP;
    }

    /** The door comes after the pickup: a rider who has not collected the food cannot be at the customer's door with it. */
    @Override
    protected void validate(OrderAssignment assignment, String pickupOtp, String deliveryOtp) {
        DeliveryStatus confirmed = assignment.getDeliveryStatus();
        if (confirmed != DeliveryStatus.OUT_FOR_DELIVERY && confirmed != DeliveryStatus.AT_DROP) {
            throw new IllegalArgumentException("Pick the order up before marking arrival at the customer.");
        }
    }
}
