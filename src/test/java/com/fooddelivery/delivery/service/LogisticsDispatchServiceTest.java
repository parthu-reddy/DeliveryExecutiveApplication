package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogisticsDispatchServiceTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    
    @Mock
    private com.fooddelivery.common.client.MapsServiceClient mapsClient;

    @Mock
    private com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository repository;

    private ObjectMapper objectMapper = new ObjectMapper();

    private LogisticsDispatchService logisticsDispatchService;

    @BeforeEach
    void setUp() {
        logisticsDispatchService = new LogisticsDispatchService(kafkaTemplate, objectMapper, mapsClient, repository);
    }

    @Test
    void dispatchNearestDriver_ShouldPublishEvent() {
        UUID orderId = UUID.randomUUID();
        when(kafkaTemplate.send(org.mockito.ArgumentMatchers.<org.springframework.messaging.Message<String>>any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Act
        logisticsDispatchService.dispatchNearestDriver("BLR", 5.0, 12.9716, 77.5946, 12.9352, 77.6245, "Test address", orderId, java.util.Collections.emptyList());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<org.springframework.messaging.Message<String>> msgCaptor =
                ArgumentCaptor.forClass(org.springframework.messaging.Message.class);
        verify(kafkaTemplate).send(msgCaptor.capture());
        org.springframework.messaging.Message<String> sent = msgCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(sent.getHeaders().get("eventId")).isNotNull();
        org.assertj.core.api.Assertions.assertThat(
                sent.getHeaders().get(org.springframework.kafka.support.KafkaHeaders.TOPIC))
            .isEqualTo("platform.logistics.dispatch");
        String payload = sent.getPayload();
        assertThat(payload).contains(orderId.toString());
        assertThat(payload).contains("12.9716");
        assertThat(payload).contains("77.5946");
        assertThat(payload).contains("\"dispatchCityId\":\"BLR\"");
        assertThat(payload).contains("\"fleetSearchRadiusKm\":5.0");
    }

    @Test
    void releaseDriverLock_doesNotReturnAnOfflineRiderToThePool() {
        UUID driverId = UUID.randomUUID();
        when(repository.findById(driverId)).thenReturn(java.util.Optional.of(rider(driverId,
                com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.OFFLINE)));

        logisticsDispatchService.releaseDriverLock(driverId.toString());

        org.mockito.Mockito.verifyNoInteractions(mapsClient);
    }

    @Test
    void releaseDriverLock_doesNotReturnARiderOnDeliveryToThePool() {
        UUID driverId = UUID.randomUUID();
        when(repository.findById(driverId)).thenReturn(java.util.Optional.of(rider(driverId,
                com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.ON_DELIVERY)));

        logisticsDispatchService.releaseDriverLock(driverId.toString());

        org.mockito.Mockito.verifyNoInteractions(mapsClient);
    }

    @Test
    void releaseDriverLock_returnsAnOnlineRiderToThePool() {
        UUID driverId = UUID.randomUUID();
        when(repository.findById(driverId)).thenReturn(java.util.Optional.of(rider(driverId,
                com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.ONLINE)));

        logisticsDispatchService.releaseDriverLock(driverId.toString());

        ArgumentCaptor<com.fooddelivery.common.dto.maps.SetAvailabilityRequest> req =
                ArgumentCaptor.forClass(com.fooddelivery.common.dto.maps.SetAvailabilityRequest.class);
        verify(mapsClient).releaseDriver(req.capture());
        assertThat(req.getValue().getDriverId()).isEqualTo(driverId.toString());
        assertThat(req.getValue().getCityId()).isEqualTo("BLR");
    }

    private static com.fooddelivery.delivery.entity.DeliveryExecutive rider(
            UUID id, com.fooddelivery.delivery.enums.DeliveryExecutiveStatus status) {
        com.fooddelivery.delivery.entity.DeliveryExecutive e = new com.fooddelivery.delivery.entity.DeliveryExecutive();
        e.setId(id);
        e.setCityId("BLR");
        e.setStatus(status);
        return e;
    }
}
