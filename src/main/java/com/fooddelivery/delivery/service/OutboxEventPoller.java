package com.fooddelivery.delivery.service;

import com.fooddelivery.delivery.entity.OutboxEventEntity;
import com.fooddelivery.delivery.repository.IOutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxEventPoller {

    private final IOutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private static final String TOPIC = com.fooddelivery.common.constants.KafkaConstants.TOPIC_ORDER_EVENTS;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void pollOutboxEvents() {
        List<OutboxEventEntity> unprocessedEvents = outboxEventRepository.findUnprocessedEventsAndLock(List.of(com.fooddelivery.common.constants.AppConstants.OUTBOX_STATUS_UNPROCESSED, com.fooddelivery.common.constants.AppConstants.OUTBOX_STATUS_FAILED));
        
        for (OutboxEventEntity event : unprocessedEvents) {
            try {
                kafkaTemplate.send(TOPIC, event.getAggregateId(), event.getPayload()).get(3, java.util.concurrent.TimeUnit.SECONDS);
                
                event.setStatus(com.fooddelivery.common.constants.AppConstants.OUTBOX_STATUS_PROCESSED);
                event.setProcessedAt(LocalDateTime.now());
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.error("Failed to publish outbox event ID: {}", event.getId(), e);
                event.setStatus(com.fooddelivery.common.constants.AppConstants.OUTBOX_STATUS_FAILED);
                event.setErrorMessage(e.getMessage());
                outboxEventRepository.save(event);
            }
        }
    }
}
