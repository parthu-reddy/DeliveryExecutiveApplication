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
@lombok.RequiredArgsConstructor
public class OrderEventConsumer {
private final ObjectMapper objectMapper;

    private final com.fooddelivery.common.event.EventBinder eventBinder;
    
    private static final java.util.Map<com.fooddelivery.common.constants.EventType,
            Class<? extends com.fooddelivery.common.event.OrderScopedEvent>> EVENT_CLASSES =
            new java.util.EnumMap<>(com.fooddelivery.common.constants.EventType.class);
    static {
        EVENT_CLASSES.put(com.fooddelivery.common.constants.EventType.ORDER_ACCEPTED, com.fooddelivery.common.event.OrderAcceptedEvent.class);
        EVENT_CLASSES.put(com.fooddelivery.common.constants.EventType.ORDER_CANCELLED, com.fooddelivery.common.event.OrderCancelledEvent.class);
        EVENT_CLASSES.put(com.fooddelivery.common.constants.EventType.ORDER_CANCELLED_BY_ADMIN, com.fooddelivery.common.event.OrderCancelledByAdminEvent.class);
        EVENT_CLASSES.put(com.fooddelivery.common.constants.EventType.ORDER_CANCELLED_BY_RESTAURANT, com.fooddelivery.common.event.OrderCancelledByRestaurantEvent.class);
        EVENT_CLASSES.put(com.fooddelivery.common.constants.EventType.ORDER_CANCELLED_BY_CUSTOMER, com.fooddelivery.common.event.OrderCancelledByCustomerEvent.class);
        EVENT_CLASSES.put(com.fooddelivery.common.constants.EventType.ORDER_REJECTED, com.fooddelivery.common.event.OrderRejectedEvent.class);
        EVENT_CLASSES.put(com.fooddelivery.common.constants.EventType.DELIVERY_FAILED, com.fooddelivery.common.event.DeliveryFailedEvent.class);
        EVENT_CLASSES.put(com.fooddelivery.common.constants.EventType.ORDER_DELIVERED, com.fooddelivery.common.event.DeliveredEvent.class);
        EVENT_CLASSES.put(com.fooddelivery.common.constants.EventType.DISPATCH_CANDIDATE_FOUND, com.fooddelivery.common.event.DispatchCandidateFoundEvent.class);
        EVENT_CLASSES.put(com.fooddelivery.common.constants.EventType.FORCE_ASSIGN_DRIVER, com.fooddelivery.common.event.ForceAssignDriverEvent.class);
        EVENT_CLASSES.put(com.fooddelivery.common.constants.EventType.ORDER_STATUS_UPDATED, com.fooddelivery.common.event.OrderStatusUpdatedEvent.class);
        EVENT_CLASSES.put(com.fooddelivery.common.constants.EventType.ORDER_DRIVER_REJECTED, com.fooddelivery.common.event.OrderDriverRejectedEvent.class);
        // These six were declared by a strategy but had no entry here, so the listener fell back to
        // handing the strategy a raw JsonNode. The strategies dispatch on `instanceof`, so no branch
        // matched, nothing threw, and the event was silently dropped -- driver assignment and
        // dispatch-failure handling stopped working with no error. StrategyEventTypeCoverageTest
        // now fails if a strategy declares an event type that is missing here.
        EVENT_CLASSES.put(com.fooddelivery.common.constants.EventType.ORDER_PREPARING, com.fooddelivery.common.event.OrderPreparingEvent.class);
        EVENT_CLASSES.put(com.fooddelivery.common.constants.EventType.ORDER_READY, com.fooddelivery.common.event.OrderReadyEvent.class);
        EVENT_CLASSES.put(com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED, com.fooddelivery.common.event.DriverAssignedEvent.class);
        EVENT_CLASSES.put(com.fooddelivery.common.constants.EventType.DISPATCH_FAILED, com.fooddelivery.common.event.DispatchFailedEvent.class);
        EVENT_CLASSES.put(com.fooddelivery.common.constants.EventType.MANUAL_INTERVENTION_REQUIRED, com.fooddelivery.common.event.ManualInterventionRequiredEvent.class);
        EVENT_CLASSES.put(com.fooddelivery.common.constants.EventType.ORDER_DELAY_REJECTED, com.fooddelivery.common.event.OrderDelayRejectedEvent.class);
    }

    private final com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy<?>[] strategies;
    private final java.util.Map<String, java.util.List<com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy<?>>> strategyMap = new java.util.HashMap<>();
    private final IIdempotencyKeyRepository idempotencyKeyRepository;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;


    @jakarta.annotation.PostConstruct
    public void init() {
        for (com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy<?> strategy : strategies) {
            for (String eventType : strategy.getEventTypes()) {
                this.strategyMap.computeIfAbsent(eventType, k -> new java.util.ArrayList<>()).add(strategy);
            }
        }
    }

    @RetryableTopic(attempts = "4", backoff = @Backoff(delay = 2000, multiplier = 2.0, maxDelay = 10000), exclude = {com.fooddelivery.common.event.EventBindingException.class}, traversingCauses = "true")
    @KafkaListener(topics = com.fooddelivery.common.constants.KafkaConstants.TOPIC_ORDER_EVENTS, groupId = com.fooddelivery.common.constants.KafkaConstants.GROUP_DELIVERY_SERVICE + "-ordereventconsumer")
    public void consumeOrderEvent(String message, @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> headers) {
        log.info("Consumed event from {}: {}", com.fooddelivery.common.constants.KafkaConstants.TOPIC_ORDER_EVENTS, message);
        
        // Idempotency check
        String eventId = com.fooddelivery.common.util.KafkaHeaderUtils.extractHeaderValue(headers, "eventId");
        if (eventId == null) {
            throw new IllegalArgumentException("Missing eventId header");
        }
        
        String idempotencyKeyStr = "processed_event:delivery:" + eventId;
        
        try {
            transactionTemplate.execute(status -> {
                int claimed = idempotencyKeyRepository.tryClaim(idempotencyKeyStr);
                if (claimed == 0) {
                    log.info("Duplicate event detected (key={}), ignoring.", idempotencyKeyStr);
                    return null;
                }

                try {
                    JsonNode root = objectMapper.readTree(message);
                    String eventType = com.fooddelivery.common.util.KafkaHeaderUtils.extractEventType(headers, root);
                    if (eventType == null) {
                        // EventType.valueOf(null) is a NullPointerException, which the catch below
                        // turns into a RuntimeException, four retries and a DLT entry. Before
                        // binding, a null type simply found no strategy and was ignored.
                        log.warn("Missing eventType on order-events. Ignoring.");
                        return null;
                    }
                    final com.fooddelivery.common.constants.EventType type;
                    try {
                        type = com.fooddelivery.common.constants.EventType.valueOf(eventType);
                    } catch (IllegalArgumentException e) {
                        log.info("Unknown event type {} on order-events. Ignoring.", eventType);
                        return null;
                    }

                    java.util.List<com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy<?>> matchedStrategies = strategyMap.get(eventType);
                    if (matchedStrategies != null && !matchedStrategies.isEmpty()) {
                        Class<? extends com.fooddelivery.common.event.OrderScopedEvent> clazz = EVENT_CLASSES.get(type);
                        if (clazz == null) {
                            // Never hand a strategy an unbound JsonNode: they dispatch on
                            // `instanceof`, so it matches nothing and the event vanishes without an
                            // error. Refuse loudly instead -- a strategy wants this event and there
                            // is no class to give it.
                            throw new IllegalStateException(
                                    "A strategy handles " + eventType + " but EVENT_CLASSES has no "
                                    + "typed class for it; the event would be silently dropped.");
                        }
                        com.fooddelivery.common.event.OrderScopedEvent typedEvent = eventBinder.bindIf(type, eventType, message, clazz)
                                .orElseThrow(() -> new IllegalStateException(
                                        "bindIf returned empty for " + eventType
                                                + " despite an exact event-type match"));
                        for (com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy<?> strategy : matchedStrategies) {
                            strategy.dispatch(typedEvent, eventType);
                        }
                    } else {
                        if (com.fooddelivery.common.constants.EventType.ORDER_PLACED_COD.name().equals(eventType)) {
                            log.info("Ignoring ORDER_PLACED_COD in delivery app: no action needed until restaurant accepts");
                        } else {
                            log.info("No strategy mapped for event type: {}. Ignoring in DeliveryExecutiveApplication.", eventType);
                        }
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
        meterRegistry.counter("kafka.dlt.messages", "service", "delivery-executive-application").increment();
        // Implementation for poison pill storage/alerting goes here
    }
}
