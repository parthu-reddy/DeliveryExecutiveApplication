package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
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
    private final java.util.Map<String, com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy> strategyMap;

    public OrderEventConsumer(
            ObjectMapper objectMapper, 
            com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy[] strategies) {
        this.objectMapper = objectMapper;
        this.strategies = strategies;
        this.strategyMap = new java.util.HashMap<>();
        for (com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy strategy : strategies) {
            for (String eventType : strategy.getEventTypes()) {
                this.strategyMap.put(eventType, strategy);
            }
        }
    }

    @KafkaListener(topics = com.fooddelivery.common.constants.KafkaConstants.TOPIC_ORDER_EVENTS, groupId = com.fooddelivery.common.constants.KafkaConstants.GROUP_DELIVERY_SERVICE)
    public void consumeOrderEvent(String message, @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> headers) {
        log.info("Consumed event from {}: {}", com.fooddelivery.common.constants.KafkaConstants.TOPIC_ORDER_EVENTS, message);
        
        try {
            JsonNode root = objectMapper.readTree(message);
            String jsonEventType = root.path("eventType").asText(null);
            
            String headerEventType = null;
            Object eventTypeObj = headers.get("eventType");
            if (eventTypeObj != null) {
                if (eventTypeObj instanceof byte[]) {
                    headerEventType = new String((byte[]) eventTypeObj, java.nio.charset.StandardCharsets.UTF_8);
                } else if (eventTypeObj.getClass().getName().contains("NonTrustedHeaderType")) {
                    String str = eventTypeObj.toString();
                    if (str.contains("headerValue=")) {
                        int start = str.indexOf("\"") + 1;
                        if (start > 0) {
                            int end = str.indexOf("\"", start);
                            if (end > start) {
                                headerEventType = str.substring(start, end);
                            } else {
                                headerEventType = str;
                            }
                        } else {
                            headerEventType = str;
                        }
                    } else {
                        headerEventType = str;
                    }
                } else {
                    headerEventType = eventTypeObj.toString();
                }
            }
            
            String eventType = headerEventType != null ? headerEventType : jsonEventType;
            
            com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy strategy = strategyMap.get(eventType);
            if (strategy != null) {
                strategy.process(root, eventType);
            } else {
                log.info("No strategy mapped for event type: {}. Ignoring in DeliveryExecutiveApplication.", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process order event in DeliveryExecutiveApplication", e);
            throw new RuntimeException("Failed to process order event in DeliveryExecutiveApplication", e);
        }
    }
}
