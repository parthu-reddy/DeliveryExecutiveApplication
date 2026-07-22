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
    private com.fooddelivery.delivery.client.MapsClient mapsClient;

    private ObjectMapper objectMapper = new ObjectMapper();

    private LogisticsDispatchService logisticsDispatchService;

    @BeforeEach
    void setUp() {
        logisticsDispatchService = new LogisticsDispatchService(kafkaTemplate, objectMapper, mapsClient);
    }

    @Test
    void dispatchNearestDriver_ShouldPublishEvent() {
        UUID orderId = UUID.randomUUID();
        when(kafkaTemplate.send(eq("platform.logistics.dispatch"), eq(orderId.toString()), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Act
        logisticsDispatchService.dispatchNearestDriver(12.9716, 77.5946, 12.9352, 77.6245, "BLR", orderId, java.util.Collections.emptyList());

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("platform.logistics.dispatch"), eq(orderId.toString()), payloadCaptor.capture());

        String payload = payloadCaptor.getValue();
        assertThat(payload).contains(orderId.toString());
        assertThat(payload).contains("12.9716");
        assertThat(payload).contains("77.5946");
    }
}
