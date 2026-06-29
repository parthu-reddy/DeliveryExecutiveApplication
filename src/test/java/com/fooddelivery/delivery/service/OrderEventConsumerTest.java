package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock
    private LogisticsDispatchService logisticsDispatchService;

    private ObjectMapper objectMapper;
    private OrderEventConsumer orderEventConsumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        orderEventConsumer = new OrderEventConsumer(objectMapper, logisticsDispatchService);
    }

    @Test
    void consumeOrderEvent_ShouldDispatchDriver_WhenOrderAcceptedWithLocation() {
        UUID orderId = UUID.randomUUID();
        double lat = 12.9716;
        double lng = 77.5946;
        
        String message = String.format("{\"eventType\":\"ORDER_ACCEPTED\", \"orderId\":\"%s\", \"restaurantLat\":%f, \"restaurantLng\":%f}", 
                orderId, lat, lng);

        orderEventConsumer.consumeOrderEvent(message, null);

        verify(logisticsDispatchService).dispatchNearestDriver(lat, lng, orderId);
    }

    @Test
    void consumeOrderEvent_ShouldNotDispatchDriver_WhenOrderAcceptedWithoutLocation() {
        UUID orderId = UUID.randomUUID();
        
        String message = String.format("{\"eventType\":\"ORDER_ACCEPTED\", \"orderId\":\"%s\"}", orderId);

        orderEventConsumer.consumeOrderEvent(message, null);

        verify(logisticsDispatchService, never()).dispatchNearestDriver(anyDouble(), anyDouble(), any(UUID.class));
    }

    @Test
    void consumeOrderEvent_ShouldIgnoreOtherEvents() {
        UUID orderId = UUID.randomUUID();
        
        String message = String.format("{\"eventType\":\"ORDER_CREATED\", \"orderId\":\"%s\"}", orderId);

        orderEventConsumer.consumeOrderEvent(message, null);

        verify(logisticsDispatchService, never()).dispatchNearestDriver(anyDouble(), anyDouble(), any(UUID.class));
    }
}
