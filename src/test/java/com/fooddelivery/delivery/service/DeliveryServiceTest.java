package com.fooddelivery.delivery.service;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.repository.IOutboxEventRepository;
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
    private IOutboxEventRepository outboxEventRepository;

    @Mock
    private LogisticsDispatchService logisticsDispatchService;

    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Mock
    private org.springframework.data.redis.core.ValueOperations<String, String> valueOperations;

    @Mock
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

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
        
        org.mockito.Mockito.lenient().when(transactionTemplate.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(new org.springframework.transaction.support.SimpleTransactionStatus());
        });
    }

    @Test
    void acceptOrderPing_ShouldPublishEventAndUpdateStatus() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("order:driver:lock:" + orderId), eq(driverId.toString()), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(repository.findById(driverId)).thenReturn(Optional.of(executive));

        deliveryService.acceptOrderPing(driverId, orderId);

        ArgumentCaptor<com.fooddelivery.delivery.entity.OutboxEventEntity> outboxCaptor = ArgumentCaptor.forClass(com.fooddelivery.delivery.entity.OutboxEventEntity.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());

        assertThat(outboxCaptor.getValue().getPayload()).contains(com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED);
        assertThat(executive.getStatus()).isEqualTo(DeliveryExecutiveStatus.ON_DELIVERY);
        verify(repository).save(executive);
    }

    @Test
    void rejectOrderPing_ShouldPublishEventAndReleaseLock() {
        deliveryService.rejectOrderPing(driverId, orderId);

        verify(logisticsDispatchService).releaseDriverLock(driverId.toString());

        ArgumentCaptor<com.fooddelivery.delivery.entity.OutboxEventEntity> outboxCaptor = ArgumentCaptor.forClass(com.fooddelivery.delivery.entity.OutboxEventEntity.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());

        assertThat(outboxCaptor.getValue().getPayload()).contains(com.fooddelivery.common.constants.EventType.ORDER_DRIVER_REJECTED);
    }

    @Test
    void updateOrderStatus_Delivered_ShouldUpdateDriverStatusAndReleaseLock() {
        when(repository.findById(driverId)).thenReturn(Optional.of(executive));
        executive.setStatus(DeliveryExecutiveStatus.ON_DELIVERY);

        deliveryService.updateOrderStatus(driverId, orderId, "DELIVERED");

        ArgumentCaptor<com.fooddelivery.delivery.entity.OutboxEventEntity> outboxCaptor = ArgumentCaptor.forClass(com.fooddelivery.delivery.entity.OutboxEventEntity.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());

        assertThat(outboxCaptor.getValue().getPayload()).contains(com.fooddelivery.common.constants.EventType.ORDER_DELIVERED);
        assertThat(executive.getStatus()).isEqualTo(DeliveryExecutiveStatus.ONLINE);
        verify(repository).save(executive);
        verify(logisticsDispatchService).releaseDriverLock(driverId.toString());
    }
}
