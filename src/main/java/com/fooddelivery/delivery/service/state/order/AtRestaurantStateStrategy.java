package com.fooddelivery.delivery.service.state.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.LogisticsDispatchService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

@Component
@lombok.extern.slf4j.Slf4j
public class AtRestaurantStateStrategy extends AbstractDeliveryOrderState {
    public AtRestaurantStateStrategy(org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
                 com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                 org.springframework.transaction.support.TransactionTemplate transactionTemplate,
                 com.fooddelivery.common.outbox.repository.OutboxEventRepository outboxEventRepository,
                 com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository repository,
                 com.fooddelivery.delivery.service.LogisticsDispatchService logisticsDispatchService) {
        super(redisTemplate, objectMapper, transactionTemplate, outboxEventRepository, repository, logisticsDispatchService);
    }


    @Override
    public DeliveryStatus getSupportedStatus() {
        return DeliveryStatus.AT_RESTAURANT;
    }

    @Override
    protected com.fooddelivery.common.constants.EventType getEventType() {
        return EventType.ORDER_AT_RESTAURANT;
    }
}
