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
    
    @Mock
    private com.fooddelivery.common.repository.IIdempotencyKeyRepository idempotencyKeyRepository;
    
    @Mock
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @Mock
    private DeliveryEventStrategy mockAcceptedStrategy;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
        when(mockAcceptedStrategy.getEventTypes()).thenReturn(java.util.Collections.singletonList(com.fooddelivery.common.constants.EventType.ORDER_ACCEPTED.name()));

        jakarta.validation.Validator validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        com.fooddelivery.common.event.EventBinder eventBinder = new com.fooddelivery.common.event.EventBinder(objectMapper, validator);
        orderEventConsumer = new OrderEventConsumer(
            objectMapper,
            eventBinder,
            new DeliveryEventStrategy[]{mockAcceptedStrategy},
            idempotencyKeyRepository,
            transactionTemplate,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
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
                + "\"restaurantLat\":12.9,\"restaurantLng\":77.6,\"deliveryLat\":12.95,\"deliveryLng\":77.65,"
                + "\"pickupOtp\":\"123456\",\"deliveryOtp\":\"654321\","
                + "\"dispatchCityId\":\"BLR\",\"fleetSearchRadiusKm\":5.0}";
        
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

    /** The consumer requires an eventId header (I-5); OutboxProcessor always sets one. */
    private java.util.Map<String, Object> headersWithEventId() {
        java.util.Map<String, Object> h = new java.util.HashMap<>();
        h.put("eventId", java.util.UUID.randomUUID().toString());
        return h;
    }
}
