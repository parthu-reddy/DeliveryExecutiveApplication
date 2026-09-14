package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fooddelivery.common.constants.AggregateType;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.common.enums.OutboxStatus;
import com.fooddelivery.common.outbox.entity.OutboxEventEntity;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Reusable helper to construct OutboxEventEntity instances.
 * Eliminates the 5 identical copy-pasted blocks in DeliveryService.
 */
@Component
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class OutboxEventHelper {
private final ObjectMapper objectMapper;

    /**
     * Creates a fully-built OutboxEventEntity from a payload map.
     *
     * @param aggregateType The aggregate root type (e.g., ORDER)
     * @param aggregateId   The aggregate root identifier
     * @param eventType     The event type being emitted
     * @param payloadMap    Key-value pairs to serialize as JSON payload
     * @return A ready-to-persist OutboxEventEntity
     */
    public OutboxEventEntity createOutboxEvent(AggregateType aggregateType, String aggregateId, EventType eventType, Object payloadObject) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(payloadObject);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize " + eventType + " payload", e);
        }
        return OutboxEventEntity.builder().id(UUID.randomUUID()).aggregateType(aggregateType).aggregateId(aggregateId).eventType(eventType).payload(payload).createdAt(LocalDateTime.now()).status(OutboxStatus.UNPROCESSED).build();
    }

}
