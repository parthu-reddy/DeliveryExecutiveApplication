package com.fooddelivery.delivery.service.state.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fooddelivery.common.constants.AppConstants;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.common.enums.OutboxStatus;
import com.fooddelivery.common.outbox.entity.OutboxEventEntity;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.LogisticsDispatchService;
import com.fooddelivery.delivery.service.state.DeliveryExecutiveStateFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.UUID;
@lombok.extern.slf4j.Slf4j

public abstract class AbstractDeliveryOrderState implements DeliveryOrderStateStrategy {
protected final StringRedisTemplate redisTemplate;
    protected final ObjectMapper objectMapper;
    protected final TransactionTemplate transactionTemplate;
    protected final OutboxEventRepository outboxEventRepository;
    protected final IDeliveryExecutiveRepository repository;
    protected final LogisticsDispatchService logisticsDispatchService;

    public AbstractDeliveryOrderState(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, TransactionTemplate transactionTemplate, OutboxEventRepository outboxEventRepository, IDeliveryExecutiveRepository repository, LogisticsDispatchService logisticsDispatchService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.outboxEventRepository = outboxEventRepository;
        this.repository = repository;
        this.logisticsDispatchService = logisticsDispatchService;
    }

    @Override
    public void handleStatusUpdate(UUID driverId, UUID orderId, DeliveryStatus status, String pickupOtp, String deliveryOtp, Boolean goOfflineAfter) {
        log.info("Driver {} updating order {} to {}", driverId, orderId, status);
        String currentAssignee = redisTemplate.opsForValue().get(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + orderId);
        if (currentAssignee != null && !driverId.toString().equals(currentAssignee)) {
            log.info("Idempotent/Invalid update: Order {} is assigned to another driver {}", orderId, currentAssignee);
            return;
        }
        validate(driverId, orderId, pickupOtp, deliveryOtp);
        transactionTemplate.execute(txStatus -> {
            saveOutboxEvent(orderId, status, pickupOtp, deliveryOtp);
            updateExecutiveState(driverId, goOfflineAfter);
            return null;
        });
        postProcess(driverId, orderId, goOfflineAfter);
    }

    protected void validate(UUID driverId, UUID orderId, String pickupOtp, String deliveryOtp) {
        // Default no-op. Override in subclasses if validation is needed.
    }

    protected void saveOutboxEvent(UUID orderId, DeliveryStatus status, String pickupOtp, String deliveryOtp) {
        ObjectNode payloadNode = objectMapper.createObjectNode();
        payloadNode.put("eventType", getEventType().name());
        payloadNode.put("orderId", orderId.toString());
        payloadNode.put("status", status.name());
        if (pickupOtp != null && !pickupOtp.isEmpty()) {
            payloadNode.put("pickupOtp", pickupOtp);
        }
        if (deliveryOtp != null && !deliveryOtp.isEmpty()) {
            payloadNode.put("deliveryOtp", deliveryOtp);
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(payloadNode);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize status update payload", e);
        }
        OutboxEventEntity outboxEvent = OutboxEventEntity.builder().id(UUID.randomUUID()).aggregateType(com.fooddelivery.common.constants.AggregateType.ORDER).aggregateId(orderId.toString()).eventType(getEventType()).payload(payload).createdAt(LocalDateTime.now()).status(OutboxStatus.UNPROCESSED).build();
        log.info("Triggering event: {} for order: {}", getEventType().name(), orderId);
        outboxEventRepository.save(outboxEvent);
    }

    protected void updateExecutiveState(UUID driverId, Boolean goOfflineAfter) {
        // Default no-op. Override for DELIVERED / FAILED.
    }

    protected void postProcess(UUID driverId, UUID orderId, Boolean goOfflineAfter) {
        // Default no-op. Override for DELIVERED / FAILED.
    }

    protected abstract com.fooddelivery.common.constants.EventType getEventType();
}
