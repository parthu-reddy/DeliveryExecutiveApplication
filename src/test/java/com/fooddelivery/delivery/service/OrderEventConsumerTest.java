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
    private DeliveryEventStrategy mockAcceptedStrategy;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        
        when(mockAcceptedStrategy.getEventTypes()).thenReturn(java.util.Collections.singletonList("ORDER_ACCEPTED"));

        orderEventConsumer = new OrderEventConsumer(objectMapper, new DeliveryEventStrategy[]{mockAcceptedStrategy});
    }

    @Test
    void consumeOrderEvent_ShouldProcessOrderAcceptedEvent() throws Exception {
        UUID orderId = UUID.randomUUID();
        
        String message = String.format("{\"eventType\":\"ORDER_ACCEPTED\", \"orderId\":\"%s\", \"restaurantLat\":12.9716, \"restaurantLng\":77.5946}", 
                orderId);

        orderEventConsumer.consumeOrderEvent(message, null);
        
        verify(mockAcceptedStrategy).process(any(), eq("ORDER_ACCEPTED"));
    }

    @Test
    void consumeOrderEvent_ShouldIgnoreOtherEvents() throws Exception {
        UUID orderId = UUID.randomUUID();
        
        String message = String.format("{\"eventType\":\"ORDER_CREATED\", \"orderId\":\"%s\"}", orderId);

        orderEventConsumer.consumeOrderEvent(message, null);

        verify(mockAcceptedStrategy, never()).process(any(), anyString());
    }
}
