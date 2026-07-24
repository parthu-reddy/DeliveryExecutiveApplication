package com.fooddelivery.delivery.service;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {

    @Mock
    private IDeliveryExecutiveRepository repository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private LogisticsDispatchService logisticsDispatchService;

    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Mock
    private org.springframework.data.redis.core.ValueOperations<String, String> valueOperations;
    
    @Mock
    private org.springframework.data.redis.core.ZSetOperations<String, String> zSetOperations;

    @Mock
    private org.springframework.data.redis.core.SetOperations<String, String> setOperations;

    @Mock
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @org.mockito.Spy
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @InjectMocks
    private DeliveryService deliveryService;

    private UUID driverId;
    private UUID orderId;
    private DeliveryExecutive executive;

    @BeforeEach
    void setUp() {
        driverId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        executive = new DeliveryExecutive();
        executive.setId(driverId);
        executive.setStatus(DeliveryExecutiveStatus.ONLINE);
        
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        org.mockito.Mockito.lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
        
        org.mockito.Mockito.lenient().when(transactionTemplate.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(new org.springframework.transaction.support.SimpleTransactionStatus());
        });
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action = invocation.getArgument(0);
            action.accept(new org.springframework.transaction.support.SimpleTransactionStatus());
            return null;
        }).when(transactionTemplate).executeWithoutResult(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void acceptOrderPing_ShouldPublishEventAndUpdateStatus() {
        when(redisTemplate.execute(
                org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<java.util.List>>any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyString()
        )).thenReturn(java.util.List.of("SUCCESS_EMPTY"));

        when(repository.findLockedById(driverId)).thenReturn(Optional.of(executive));
        when(valueOperations.get("order:driver:lock:" + orderId)).thenReturn(driverId.toString());

        deliveryService.acceptOrderPing(driverId, orderId);

        ArgumentCaptor<com.fooddelivery.common.outbox.entity.OutboxEventEntity> outboxCaptor = ArgumentCaptor.forClass(com.fooddelivery.common.outbox.entity.OutboxEventEntity.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());

        assertThat(outboxCaptor.getValue().getPayload()).contains(com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED.name());
        assertThat(executive.getStatus()).isEqualTo(DeliveryExecutiveStatus.ON_DELIVERY);
        verify(repository).save(executive);
    }

    @Test
    void rejectOrderPing_ShouldPublishEventAndReleaseLock() {
        when(redisTemplate.execute(
                org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<String>>any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyString()
        )).thenReturn("LAST_REJECT");

        deliveryService.rejectOrderPing(driverId, orderId);

        verify(logisticsDispatchService).releaseDriverLock(driverId.toString());

        ArgumentCaptor<com.fooddelivery.common.outbox.entity.OutboxEventEntity> outboxCaptor = ArgumentCaptor.forClass(com.fooddelivery.common.outbox.entity.OutboxEventEntity.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());

        assertThat(outboxCaptor.getValue().getPayload()).contains(com.fooddelivery.common.constants.EventType.ORDER_DRIVER_REJECTED.name());
    }

    @Test
    void updateOrderStatus_Delivered_ShouldUpdateDriverStatusAndReleaseLock() {
        com.fooddelivery.delivery.service.state.order.DeliveryOrderStateFactory mockFactory = org.mockito.Mockito.mock(com.fooddelivery.delivery.service.state.order.DeliveryOrderStateFactory.class);
        org.springframework.test.util.ReflectionTestUtils.setField(deliveryService, "deliveryOrderStateFactory", mockFactory);
        com.fooddelivery.delivery.service.state.order.DeliveryOrderStateStrategy mockStrategy = org.mockito.Mockito.mock(com.fooddelivery.delivery.service.state.order.DeliveryOrderStateStrategy.class);
        when(mockFactory.getStrategy(com.fooddelivery.common.enums.DeliveryStatus.DELIVERED)).thenReturn(mockStrategy);

        deliveryService.updateOrderStatus(driverId, orderId, com.fooddelivery.common.enums.DeliveryStatus.DELIVERED, null, null, null);

        verify(mockStrategy).handleStatusUpdate(driverId, orderId, com.fooddelivery.common.enums.DeliveryStatus.DELIVERED, null, null, null);
    }
}
