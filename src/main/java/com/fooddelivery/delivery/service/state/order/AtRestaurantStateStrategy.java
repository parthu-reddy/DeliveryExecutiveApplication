package com.fooddelivery.delivery.service.state.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.common.enums.OrderStatus;
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
public class AtRestaurantStateStrategy extends AbstractDeliveryOrderState {

    public AtRestaurantStateStrategy(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, TransactionTemplate transactionTemplate, OutboxEventRepository outboxEventRepository, IDeliveryExecutiveRepository repository, LogisticsDispatchService logisticsDispatchService) {
        super(redisTemplate, objectMapper, transactionTemplate, outboxEventRepository, repository, logisticsDispatchService);
    }

    @Override
    public OrderStatus getSupportedStatus() {
        return OrderStatus.AT_RESTAURANT;
    }

    @Override
    protected com.fooddelivery.common.constants.EventType getEventType() {
        return EventType.ORDER_AT_RESTAURANT;
    }
}
