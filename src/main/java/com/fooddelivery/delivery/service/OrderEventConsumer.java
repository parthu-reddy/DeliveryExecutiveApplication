package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;

import java.util.UUID;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;

@Service
@Slf4j
public class OrderEventConsumer {

    private final ObjectMapper objectMapper;
    
    private final com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy[] strategies;
    private final java.util.Map<String, java.util.List<com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy>> strategyMap;

    public OrderEventConsumer(
            ObjectMapper objectMapper, 
            com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy[] strategies) {
        this.objectMapper = objectMapper;
        this.strategies = strategies;
        this.strategyMap = new java.util.HashMap<>();
        for (com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy strategy : strategies) {
            for (String eventType : strategy.getEventTypes()) {
                this.strategyMap.computeIfAbsent(eventType, k -> new java.util.ArrayList<>()).add(strategy);
            }
        }
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 2000, multiplier = 2.0, maxDelay = 10000)
    )
    @KafkaListener(topics = com.fooddelivery.common.constants.KafkaConstants.TOPIC_ORDER_EVENTS, groupId = com.fooddelivery.common.constants.KafkaConstants.GROUP_DELIVERY_SERVICE)
    public void consumeOrderEvent(String message, @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> headers) {
        log.info("Consumed event from {}: {}", com.fooddelivery.common.constants.KafkaConstants.TOPIC_ORDER_EVENTS, message);
        
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
        } catch (Exception e) {
            log.error("Failed to process order event in DeliveryExecutiveApplication", e);
            throw new RuntimeException("Failed to process order event in DeliveryExecutiveApplication", e);
        }
    }

    @DltHandler
    public void handleDltMessage(String message, @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> headers) {
        log.error("Dead Letter Topic: Failed to process order event after retries. Message: {}", message);
        // Implementation for poison pill storage/alerting goes here
    }
}
