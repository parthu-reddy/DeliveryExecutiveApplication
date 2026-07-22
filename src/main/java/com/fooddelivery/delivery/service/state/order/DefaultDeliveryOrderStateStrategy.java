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
public class DefaultDeliveryOrderStateStrategy extends AbstractDeliveryOrderState {

    private final ThreadLocal<DeliveryStatus> currentStatus = new ThreadLocal<>();

    public DefaultDeliveryOrderStateStrategy(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, TransactionTemplate transactionTemplate, OutboxEventRepository outboxEventRepository, IDeliveryExecutiveRepository repository, LogisticsDispatchService logisticsDispatchService) {
        super(redisTemplate, objectMapper, transactionTemplate, outboxEventRepository, repository, logisticsDispatchService);
    }

    @Override
    public DeliveryStatus getSupportedStatus() {
        return null;
    }

    @Override
    protected com.fooddelivery.common.constants.EventType getEventType() {
        return EventType.ORDER_STATUS_UPDATED;
    }


}
