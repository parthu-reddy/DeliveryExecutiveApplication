package com.fooddelivery.common.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.KafkaConstants;
import com.fooddelivery.common.event.NotificationRequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationRouterService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void routeNotification(NotificationRequestEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            String routingKey = event.getUserId() != null ? event.getUserId().toString() : event.getExplicitRecipient();
            
            kafkaTemplate.send(KafkaConstants.TOPIC_NOTIFICATIONS_DISPATCH, routingKey, payload)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("Successfully published NotificationRequestEvent to {}", KafkaConstants.TOPIC_NOTIFICATIONS_DISPATCH);
                    } else {
                        log.error("Failed to publish NotificationRequestEvent for routingKey: {}", routingKey, ex);
                        // We rely on the producer retry configs for Kafka here.
                        // The CommunicationIntegration service handles its own DLQ.
                    }
                });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize NotificationRequestEvent", e);
        }
    }
}
