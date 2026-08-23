package com.fooddelivery.delivery.service.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class OrderAcceptedStrategyTest {
    @Mock
    private com.fooddelivery.delivery.service.LogisticsDispatchService logisticsDispatchService;

    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Mock
    private org.springframework.data.redis.core.ValueOperations<String, String> valueOperations;

    @InjectMocks
    private OrderAcceptedStrategy strategy;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testProcessFirstCallAcquiresLockAndDispatches() throws Exception {
        org.mockito.Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.when(valueOperations.setIfAbsent(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(true);

        ObjectNode node = objectMapper.createObjectNode();
        node.put("orderId", UUID.randomUUID().toString());
        node.put("restaurantLat", 12.0);
        node.put("restaurantLng", 13.0);
        
        strategy.process(node, "ORDER_ACCEPTED");
        
        org.mockito.Mockito.verify(logisticsDispatchService).dispatchNearestDriver(
            org.mockito.ArgumentMatchers.eq(12.0), org.mockito.ArgumentMatchers.eq(13.0),
            org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    void testProcessSecondCallIsNoOp() throws Exception {
        org.mockito.Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.when(valueOperations.setIfAbsent(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(false);

        ObjectNode node = objectMapper.createObjectNode();
        node.put("orderId", UUID.randomUUID().toString());
        node.put("restaurantLat", 12.0);
        node.put("restaurantLng", 13.0);
        
        strategy.process(node, "ORDER_ACCEPTED");
        
        org.mockito.Mockito.verify(logisticsDispatchService, org.mockito.Mockito.never()).dispatchNearestDriver(
            org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()
        );
    }
}
