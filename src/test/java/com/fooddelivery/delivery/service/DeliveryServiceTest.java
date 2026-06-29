package com.fooddelivery.delivery.service;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
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
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private LogisticsDispatchService logisticsDispatchService;

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
    }

    @Test
    void acceptOrderPing_ShouldPublishEventAndUpdateStatus() {
        when(repository.findById(driverId)).thenReturn(Optional.of(executive));

        deliveryService.acceptOrderPing(driverId, orderId);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("order-events"), eq(orderId.toString()), payloadCaptor.capture());

        assertThat(payloadCaptor.getValue()).contains("DRIVER_ASSIGNED");
        assertThat(executive.getStatus()).isEqualTo(DeliveryExecutiveStatus.ON_DELIVERY);
        verify(repository).save(executive);
    }

    @Test
    void rejectOrderPing_ShouldPublishEventAndReleaseLock() {
        deliveryService.rejectOrderPing(driverId, orderId);

        verify(logisticsDispatchService).releaseDriverLock(driverId.toString());

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("order-events"), eq(orderId.toString()), payloadCaptor.capture());

        assertThat(payloadCaptor.getValue()).contains("ORDER_DRIVER_REJECTED");
    }

    @Test
    void updateOrderStatus_Delivered_ShouldUpdateDriverStatusAndReleaseLock() {
        when(repository.findById(driverId)).thenReturn(Optional.of(executive));
        executive.setStatus(DeliveryExecutiveStatus.ON_DELIVERY);

        deliveryService.updateOrderStatus(driverId, orderId, "DELIVERED");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("order-events"), eq(orderId.toString()), payloadCaptor.capture());

        assertThat(payloadCaptor.getValue()).contains("ORDER_DELIVERED");
        assertThat(executive.getStatus()).isEqualTo(DeliveryExecutiveStatus.ONLINE);
        verify(repository).save(executive);
        verify(logisticsDispatchService).releaseDriverLock(driverId.toString());
    }
}
