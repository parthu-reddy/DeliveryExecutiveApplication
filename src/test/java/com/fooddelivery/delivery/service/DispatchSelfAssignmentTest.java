package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.delivery.client.OrderServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchSelfAssignmentTest {
    final OrderServiceClient orders=mock(OrderServiceClient.class);
    final KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsDispatchService service=new LogisticsDispatchService(kafka,mapper,mock(com.fooddelivery.common.client.MapsServiceClient.class),
        mock(com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository.class),orders);
    final UUID order=UUID.randomUUID(),customer=UUID.randomUUID();
    @Test void allDispatchesExcludeTheirAuthoritativeCustomerAndKeepOtherExclusions() throws Exception {
        var rejected=UUID.randomUUID().toString();when(orders.getOrderDispatchDetails(order)).thenReturn(Map.of("customerId",customer.toString()));
        when(kafka.send(org.mockito.ArgumentMatchers.<org.springframework.messaging.Message<String>>any())).thenReturn(CompletableFuture.completedFuture(null));
        service.dispatchNearestDriver("BLR",5,12,77,13,78,"Fixture address",order,List.of(rejected));
        var message=org.mockito.ArgumentCaptor.forClass(org.springframework.messaging.Message.class);verify(kafka).send(message.capture());
        var request=mapper.readValue((String)message.getValue().getPayload(),com.fooddelivery.common.event.DispatchRequestedEvent.class);
        assertEquals(List.of(rejected,customer.toString()),request.getExcludedDriverIds());verify(orders).getOrderDispatchDetails(order);
    }
    @Test void initialDispatchAddsItsCustomerEvenWithoutPreviousExclusions() throws Exception {
        when(orders.getOrderDispatchDetails(order)).thenReturn(Map.of("customerId",customer.toString()));
        when(kafka.send(org.mockito.ArgumentMatchers.<org.springframework.messaging.Message<String>>any())).thenReturn(CompletableFuture.completedFuture(null));
        service.dispatchNearestDriver("BLR",5,12,77,13,78,"Fixture address",order,null);
        var message=org.mockito.ArgumentCaptor.forClass(org.springframework.messaging.Message.class);verify(kafka).send(message.capture());
        var request=mapper.readValue((String)message.getValue().getPayload(),com.fooddelivery.common.event.DispatchRequestedEvent.class);
        assertEquals(List.of(customer.toString()),request.getExcludedDriverIds());
    }
    @Test void dispatchFailsClosedWhenCustomerCannotBeVerified() {
        when(orders.getOrderDispatchDetails(order)).thenReturn(Map.of());
        assertThrows(RuntimeException.class,()->service.dispatchNearestDriver("BLR",5,12,77,13,78,"Fixture address",order,null));verifyNoInteractions(kafka);
    }
}
