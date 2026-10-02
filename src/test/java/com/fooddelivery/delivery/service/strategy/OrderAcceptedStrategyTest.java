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

        com.fooddelivery.common.event.OrderAcceptedEvent event = new com.fooddelivery.common.event.OrderAcceptedEvent();
        event.setOrderId(UUID.randomUUID().toString());
        event.setRestaurantLat(12.0);
        event.setRestaurantLng(13.0);
        event.setDispatchCityId("BLR");
        event.setFleetSearchRadiusKm(5.0);
        event.setPickupOtp("123456");
        event.setDeliveryOtp("654321");
        
        strategy.handle(event, "ORDER_ACCEPTED");
        
        org.mockito.Mockito.verify(logisticsDispatchService).dispatchNearestDriver(
            org.mockito.ArgumentMatchers.eq("BLR"), org.mockito.ArgumentMatchers.eq(5.0),
            org.mockito.ArgumentMatchers.eq(12.0), org.mockito.ArgumentMatchers.eq(13.0),
            org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    void testProcessSecondCallIsNoOp() throws Exception {
        org.mockito.Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.when(valueOperations.setIfAbsent(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(false);

        com.fooddelivery.common.event.OrderAcceptedEvent event = new com.fooddelivery.common.event.OrderAcceptedEvent();
        event.setOrderId(UUID.randomUUID().toString());
        event.setRestaurantLat(12.0);
        event.setRestaurantLng(13.0);
        event.setDispatchCityId("BLR");
        event.setFleetSearchRadiusKm(5.0);
        event.setPickupOtp("123456");
        event.setDeliveryOtp("654321");
        
        strategy.handle(event, "ORDER_ACCEPTED");
        
        org.mockito.Mockito.verify(logisticsDispatchService, org.mockito.Mockito.never()).dispatchNearestDriver(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()
        );
    }
    @Test
    void acceptedOrderWithMoreThanFifteenMinutesRemainingIsScheduledWithoutDispatching() throws Exception {
        org.mockito.Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.when(valueOperations.setIfAbsent(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.springframework.data.redis.core.ZSetOperations<String, String> delayed = org.mockito.Mockito.mock(org.springframework.data.redis.core.ZSetOperations.class);
        org.mockito.Mockito.when(redisTemplate.opsForZSet()).thenReturn(delayed);
        com.fooddelivery.common.event.OrderAcceptedEvent event = scopedEvent();
        long readyAt = System.currentTimeMillis() + 30 * 60_000L;
        event.setEstimatedCompletionTime(readyAt);
        strategy.handle(event, "ORDER_ACCEPTED");
        org.mockito.Mockito.verify(delayed).add(com.fooddelivery.common.constants.RedisKeyConstants.QUEUE_DELAYED_DISPATCH, event.getOrderId(), readyAt - 15 * 60_000L);
        org.mockito.Mockito.verifyNoInteractions(logisticsDispatchService);
    }

    @Test
    void acceptedOrderWithinFifteenMinutesDispatchesWithoutWaitingForReady() throws Exception {
        org.mockito.Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.when(valueOperations.setIfAbsent(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        com.fooddelivery.common.event.OrderAcceptedEvent event = scopedEvent();
        event.setEstimatedCompletionTime(System.currentTimeMillis() + 14 * 60_000L);
        strategy.handle(event, "ORDER_ACCEPTED");
        org.mockito.Mockito.verify(logisticsDispatchService).dispatchNearestDriver(
            org.mockito.ArgumentMatchers.eq("BLR"), org.mockito.ArgumentMatchers.eq(5.0),
            org.mockito.ArgumentMatchers.eq(12.0), org.mockito.ArgumentMatchers.eq(13.0),
            org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(UUID.fromString(event.getOrderId())), org.mockito.ArgumentMatchers.isNull());
        org.mockito.Mockito.verify(redisTemplate, org.mockito.Mockito.never()).opsForZSet();
    }

    private static com.fooddelivery.common.event.OrderAcceptedEvent scopedEvent() {
        com.fooddelivery.common.event.OrderAcceptedEvent event = new com.fooddelivery.common.event.OrderAcceptedEvent();
        event.setOrderId(UUID.randomUUID().toString());
        event.setRestaurantLat(12.0);event.setRestaurantLng(13.0);
        event.setDispatchCityId("BLR");event.setFleetSearchRadiusKm(5.0);
        return event;
    }

}
