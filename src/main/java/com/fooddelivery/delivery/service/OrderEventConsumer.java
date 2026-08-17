package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.common.repository.IIdempotencyKeyRepository;
import com.fooddelivery.common.entity.IdempotencyKey;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@lombok.extern.slf4j.Slf4j
public class OrderEventConsumer {
    @java.lang.SuppressWarnings("all")

    private final ObjectMapper objectMapper;
    private final com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy[] strategies;
    private final java.util.Map<String, java.util.List<com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy>> strategyMap;
    private final IIdempotencyKeyRepository idempotencyKeyRepository;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    public OrderEventConsumer(ObjectMapper objectMapper, com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy[] strategies, IIdempotencyKeyRepository idempotencyKeyRepository, TransactionTemplate transactionTemplate, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.strategies = strategies;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.transactionTemplate = transactionTemplate;
        this.meterRegistry = meterRegistry;
        this.strategyMap = new java.util.HashMap<>();
        for (com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy strategy : strategies) {
            for (String eventType : strategy.getEventTypes()) {
                this.strategyMap.computeIfAbsent(eventType, k -> new java.util.ArrayList<>()).add(strategy);
            }
        }
    }

    @RetryableTopic(attempts = "4", backoff = @Backoff(delay = 2000, multiplier = 2.0, maxDelay = 10000))
    @KafkaListener(topics = com.fooddelivery.common.constants.KafkaConstants.TOPIC_ORDER_EVENTS, groupId = com.fooddelivery.common.constants.KafkaConstants.GROUP_DELIVERY_SERVICE)
    public void consumeOrderEvent(String message, @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> headers) {
        log.info("Consumed event from {}: {}", com.fooddelivery.common.constants.KafkaConstants.TOPIC_ORDER_EVENTS, message);
        
        // Idempotency check
        String eventId = com.fooddelivery.common.util.KafkaHeaderUtils.extractHeaderValue(headers, "eventId");
        if (eventId == null) {
            Long offset = (Long) headers.get(org.springframework.kafka.support.KafkaHeaders.OFFSET);
            Integer partition = (Integer) headers.get(org.springframework.kafka.support.KafkaHeaders.RECEIVED_PARTITION);
            String topic = (String) headers.get(org.springframework.kafka.support.KafkaHeaders.RECEIVED_TOPIC);
            if (offset != null && partition != null && topic != null) {
                eventId = topic + "-" + partition + "-" + offset;
            } else {
                eventId = UUID.randomUUID().toString();
            }
        }
        
        String idempotencyKeyStr = "processed_event:delivery:" + eventId;
        
        try {
            transactionTemplate.execute(status -> {
                if (idempotencyKeyRepository.existsById(idempotencyKeyStr)) {
                    log.info("Duplicate event detected (key={}), ignoring.", idempotencyKeyStr);
                    return null;
                }
                idempotencyKeyRepository.save(new IdempotencyKey(idempotencyKeyStr));

                try {
                    JsonNode root = objectMapper.readTree(message);
                    String eventType = com.fooddelivery.common.util.KafkaHeaderUtils.extractEventType(headers, root);
                    java.util.List<com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy> matchedStrategies = strategyMap.get(eventType);
                    if (matchedStrategies != null && !matchedStrategies.isEmpty()) {
                        for (com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy strategy : matchedStrategies) {
                            strategy.process(root, eventType);
                        }
                    } else {
                        log.info("No strategy mapped for event type: {}. Ignoring in DeliveryExecutiveApplication.", eventType);
                    }
                    return null;
                } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                    log.warn("Optimistic locking failure in consumeOrderEvent. Propagating for @RetryableTopic retry.");
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException("Failed to process order event in DeliveryExecutiveApplication", e);
                }
            });
        } catch (Exception e) {
            log.error("Failed to process order event in DeliveryExecutiveApplication", e);
            throw e;
        }
    }

    @DltHandler
    public void handleDltMessage(String message, @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> headers) {
        log.error("Dead Letter Topic: Failed to process order event after retries. Message: {}", message);
        meterRegistry.counter("kafka.dlt.messages", "service", "delivery-service").increment();
        // Implementation for poison pill storage/alerting goes here
    }
}
