package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy;

import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    private ObjectMapper objectMapper;
    private OrderEventConsumer orderEventConsumer;
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    
    @Mock
    private com.fooddelivery.common.repository.IIdempotencyKeyRepository idempotencyKeyRepository;
    
    @Mock
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @Mock
    private DeliveryEventStrategy mockAcceptedStrategy;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
        when(mockAcceptedStrategy.getEventTypes()).thenReturn(java.util.Collections.singletonList(com.fooddelivery.common.constants.EventType.ORDER_ACCEPTED.name()));

        jakarta.validation.Validator validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        com.fooddelivery.common.event.EventBinder eventBinder = new com.fooddelivery.common.event.EventBinder(objectMapper, validator);
        orderEventConsumer = new OrderEventConsumer(
            objectMapper,
            eventBinder,
            new DeliveryEventStrategy[]{mockAcceptedStrategy},
            idempotencyKeyRepository,
            transactionTemplate,
            meters
        );
        orderEventConsumer.init();
        
        lenient().doAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<Object> action = invocation.getArgument(0);
            return action.doInTransaction(null);
        }).when(transactionTemplate).execute(any(org.springframework.transaction.support.TransactionCallback.class));
        
        lenient().when(idempotencyKeyRepository.tryClaim(anyString())).thenReturn(1);
    }

    @Test
    void testConsumeEvent_Success() throws Exception {
        String payload = "{\"eventType\":\"ORDER_ACCEPTED\",\"orderId\":\"123e4567-e89b-12d3-a456-426614174000\","
                + "\"restaurantId\":\"223e4567-e89b-12d3-a456-426614174000\",\"paymentMethod\":\"CARD\",\"fulfilmentType\":\"DELIVERY\","
                + "\"restaurantLat\":12.9,\"restaurantLng\":77.6,\"deliveryLat\":12.95,\"deliveryLng\":77.65,"
                + "\"pickupOtp\":\"123456\",\"deliveryOtp\":\"654321\","
                + "\"dispatchCityId\":\"BLR\",\"fleetSearchRadiusKm\":5.0,\"occurredAt\":\"2026-10-25T09:30:00Z\"}";
        
        orderEventConsumer.consumeOrderEvent(payload, headersWithEventId());
        
        verify(mockAcceptedStrategy).dispatch(any(), eq(com.fooddelivery.common.constants.EventType.ORDER_ACCEPTED.name()));
    }

    @Test
    void consumeOrderEvent_ShouldIgnoreOtherEvents() throws Exception {
        UUID orderId = UUID.randomUUID();
        
        String message = String.format("{\"eventType\":\"%s\", \"orderId\":\"%s\"}", com.fooddelivery.common.constants.EventType.ORDER_CREATED.name(), orderId);

        orderEventConsumer.consumeOrderEvent(message, headersWithEventId());

        verify(mockAcceptedStrategy, never()).dispatch(any(), anyString());
    }

    /** DiningPickupBooking S17: a PICKUP order is collected at the counter, so nothing here dispatches it; it is counted. */
    @Test
    void pickupSkippedAndCounted() throws Exception {
        String payload = "{\"orderId\":\"3f2504e0-4f89-41d3-9a0c-0305e82c3301\","
                + "\"restaurantId\":\"9c8b7a65-1e2d-4f30-b5a6-7c8d9e0f1a23\",\"restaurantLat\":12.9716,\"restaurantLng\":77.5946,"
                + "\"estimatedCompletionTime\":1792921800000,\"estimatedPrepTimeMinutes\":20,\"fulfilmentType\":\"PICKUP\","
                + "\"customerName\":\"Ada\",\"paymentMethod\":\"UPI\",\"occurredAt\":\"2026-11-03T09:30:00Z\"}";
        java.util.Map<String, Object> headers = headersWithEventId();
        headers.put("eventType", "ORDER_ACCEPTED");

        orderEventConsumer.consumeOrderEvent(payload, headers);

        verify(mockAcceptedStrategy, never()).dispatch(any(), anyString());
        org.assertj.core.api.Assertions.assertThat(meters.get("delivery.order.event.skipped").tag("reason", "pickup").counter().count())
                .isEqualTo(1.0);
        org.assertj.core.api.Assertions.assertThat(meters.get("delivery.order.event.skipped").tag("reason", "dine_in").counter().count())
                .isZero();
        org.assertj.core.api.Assertions.assertThat(meters.find("delivery.order.event.skipped").tag("reason", "delivery").counter())
                .isNull();
    }

    /** The consumer requires an eventId header (I-5); OutboxProcessor always sets one. */
    private java.util.Map<String, Object> headersWithEventId() {
        java.util.Map<String, Object> h = new java.util.HashMap<>();
        h.put("eventId", java.util.UUID.randomUUID().toString());
        return h;
    }
}
