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

    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Mock
    private com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository deliveryExecutiveRepository;

    private ObjectMapper objectMapper;
    private OrderEventConsumer orderEventConsumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        orderEventConsumer = new OrderEventConsumer(objectMapper, logisticsDispatchService, redisTemplate, deliveryExecutiveRepository);
    }

    @Test
    void consumeOrderEvent_ShouldDispatchDriver_WhenOrderAcceptedWithLocation() {
        UUID orderId = UUID.randomUUID();
        double lat = 12.9716;
        double lng = 77.5946;
        
        String message = String.format("{\"eventType\":\"ORDER_ACCEPTED\", \"orderId\":\"%s\", \"restaurantLat\":%f, \"restaurantLng\":%f}", 
                orderId, lat, lng);

        when(redisTemplate.opsForValue()).thenReturn(mock(org.springframework.data.redis.core.ValueOperations.class));
        when(redisTemplate.opsForValue().setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        orderEventConsumer.consumeOrderEvent(message, null);
        
        verify(logisticsDispatchService).dispatchNearestDriver(eq(12.9716), eq(77.5946), eq(0.0), eq(0.0), eq(""), eq(orderId));
    }

    @Test
    void consumeOrderEvent_ShouldNotDispatchDriver_WhenOrderAcceptedWithoutLocation() {
        UUID orderId = UUID.randomUUID();
        
        String message = String.format("{\"eventType\":\"ORDER_ACCEPTED\", \"orderId\":\"%s\"}", orderId);

        orderEventConsumer.consumeOrderEvent(message, null);

        verify(logisticsDispatchService, never()).dispatchNearestDriver(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString(), any(UUID.class));
    }

    @Test
    void consumeOrderEvent_ShouldIgnoreOtherEvents() {
        UUID orderId = UUID.randomUUID();
        
        String message = String.format("{\"eventType\":\"ORDER_CREATED\", \"orderId\":\"%s\"}", orderId);

        orderEventConsumer.consumeOrderEvent(message, null);

        verify(logisticsDispatchService, never()).dispatchNearestDriver(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString(), any(UUID.class));
    }
}
