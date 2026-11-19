package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.common.event.EventBinder;
import com.fooddelivery.common.repository.IIdempotencyKeyRepository;
import com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** WaiterProfile Phase 5: a DINE_IN order never reaches dispatch; it is skipped and counted (S11). */
class DineInOrderEventSkipTest {

    private final DeliveryEventStrategy accepted = mock(DeliveryEventStrategy.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private OrderEventConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        when(accepted.getEventTypes()).thenReturn(List.of(EventType.ORDER_ACCEPTED.name()));
        IIdempotencyKeyRepository keys = mock(IIdempotencyKeyRepository.class);
        when(keys.tryClaim(anyString())).thenReturn(1);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any(TransactionCallback.class)))
                .thenAnswer(i -> ((TransactionCallback<Object>) i.getArgument(0)).doInTransaction(null));
        consumer = new OrderEventConsumer(mapper, new EventBinder(mapper, Validation.buildDefaultValidatorFactory().getValidator()),
                new DeliveryEventStrategy[]{accepted}, keys, tx, meters);
        consumer.init();
    }

    @Test
    void aDineInOrderAcceptedIsSkippedAndCounted() throws Exception {
        // The body and headers of RestaurantApplication's order_accepted_dine_in contract, as the producer sends them.
        consumer.consumeOrderEvent("{\"orderId\":\"3f2504e0-4f89-41d3-9a0c-0305e82c3301\","
                + "\"restaurantId\":\"9c8b7a65-1e2d-4f30-b5a6-7c8d9e0f1a23\",\"restaurantLat\":12.9716,\"restaurantLng\":77.5946,"
                + "\"estimatedCompletionTime\":1792921800000,\"estimatedPrepTimeMinutes\":20,\"fulfilmentType\":\"DINE_IN\","
                + "\"tableLabel\":\"T4\",\"customerName\":\"Ada\",\"diningSessionId\":\"4f2504e0-4f89-41d3-9a0c-0305e82c3302\",\"occurredAt\":\"2026-10-25T09:30:00Z\"}",
                Map.of("eventId", "6e7f8a9b-0c1d-4e2f-8a3b-4c5d6e7f8a9b", "eventType", "ORDER_ACCEPTED", "aggregateType", "ORDER"));

        verify(accepted, never()).dispatch(any(), anyString());
        assertThat(meters.get("delivery.order.event.skipped").tag("reason", "dine_in").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aDeliveryOrderAcceptedStillDispatches() throws Exception {
        consumer.consumeOrderEvent("{\"eventType\":\"ORDER_ACCEPTED\",\"orderId\":\"123e4567-e89b-12d3-a456-426614174000\","
                + "\"restaurantId\":\"223e4567-e89b-12d3-a456-426614174000\",\"paymentMethod\":\"CARD\",\"fulfilmentType\":\"DELIVERY\","
                + "\"restaurantLat\":12.9,\"restaurantLng\":77.6,\"deliveryLat\":12.95,\"deliveryLng\":77.65,"
                + "\"pickupOtp\":\"123456\",\"deliveryOtp\":\"654321\","
                + "\"dispatchCityId\":\"BLR\",\"fleetSearchRadiusKm\":5.0,\"occurredAt\":\"2026-10-30T13:00:00Z\"}", headers());

        verify(accepted).dispatch(any(), eq(EventType.ORDER_ACCEPTED.name()));
        assertThat(meters.get("delivery.order.event.skipped").tag("reason", "dine_in").counter().count()).isZero();
    }

    private static Map<String, Object> headers() {
        return Map.of("eventId", UUID.randomUUID().toString());
    }
}
