package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.delivery.client.CustomerServiceClient;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.repository.OrderAssignmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderAssignmentAcceptanceTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void anExpiredOfferCannotBeAcceptedEvenBeforeTheTimeoutPollerCleansItUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(List.of("EXPIRED"));
        OrderAssignmentService service = new OrderAssignmentService(
                redis,
                transaction,
                mock(OutboxEventRepository.class),
                mock(OutboxEventHelper.class),
                mock(IDeliveryExecutiveRepository.class),
                mock(LogisticsDispatchService.class),
                mock(OrderAssignmentRepository.class),
                new ObjectMapper(),
                mock(CustomerServiceClient.class));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.acceptOrderPing(UUID.randomUUID(), UUID.randomUUID()));

        assertEquals("Ping expired or invalid.", exception.getMessage());
        verifyNoInteractions(transaction);
    }
}
