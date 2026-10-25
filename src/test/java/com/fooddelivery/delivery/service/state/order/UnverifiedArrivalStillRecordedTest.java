package com.fooddelivery.delivery.service.state.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.EventType;
import com.fooddelivery.common.dto.maps.LiveRiderLocation;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.common.outbox.entity.OutboxEventEntity;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.delivery.entity.OrderAssignment;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.repository.OrderAssignmentRepository;
import com.fooddelivery.delivery.service.LogisticsDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * GPS drift must not stop a delivery: an arrival that cannot be verified is accepted and recorded with verified=false, in
 * the assignment and on the event, from the same instant (DelayPenalties Phase 2).
 */
class UnverifiedArrivalStillRecordedTest {

    private static final Instant NOW = Instant.parse("2026-10-25T09:30:00Z");
    private final UUID orderId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            // As Spring Boot configures it in the service: ISO instants, which is what the consumers bind.
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private OrderAssignmentRepository assignments;
    private OutboxEventRepository outbox;
    private ValueOperations<String, String> values;
    private StringRedisTemplate redis;
    private TransactionTemplate tx;
    private OrderAssignment assignment;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        assignments = mock(OrderAssignmentRepository.class);
        outbox = mock(OutboxEventRepository.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(i -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = i.getArgument(0);
            return cb.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });
        assignment = OrderAssignment.builder().orderId(orderId).driverId(driverId).state(OrderAssignment.State.ASSIGNED)
                .assignedAt(NOW.minusSeconds(600)).pickupLat(12.971598).pickupLng(77.594562).dropLat(12.935242).dropLng(77.6244)
                .build();
        when(assignments.findByOrderId(orderId)).thenReturn(Optional.of(assignment));
    }

    private <T extends AbstractDeliveryOrderState> T strategy(Class<T> type) throws Exception {
        return type.getConstructor(StringRedisTemplate.class, ObjectMapper.class, TransactionTemplate.class,
                        OutboxEventRepository.class, IDeliveryExecutiveRepository.class, LogisticsDispatchService.class,
                        OrderAssignmentRepository.class, Clock.class)
                .newInstance(redis, mapper, tx, outbox, mock(IDeliveryExecutiveRepository.class), mock(LogisticsDispatchService.class),
                        assignments, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private JsonNode published(EventType type) throws Exception {
        ArgumentCaptor<OutboxEventEntity> saved = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outbox).save(saved.capture());
        assertEquals(type, saved.getValue().getEventType());
        return mapper.readTree(saved.getValue().getPayload());
    }

    @Test
    void outletArrivalWithNoLivePositionIsRecordedUnverified() throws Exception {
        assertDoesNotThrow(() -> strategy(AtRestaurantStateStrategy.class)
                .handleStatusUpdate(driverId, orderId, DeliveryStatus.AT_RESTAURANT, null, null, false));

        JsonNode event = published(EventType.ORDER_AT_RESTAURANT);
        assertFalse(event.path("verified").asBoolean(true));
        assertTrue(event.path("distanceMetres").isNull());
        assertEquals(NOW, assignment.getAtRestaurantAt());
        assertEquals(Boolean.FALSE, assignment.getAtRestaurantVerified());
        assertEquals(DeliveryStatus.AT_RESTAURANT, assignment.getDeliveryStatus());
    }

    @Test
    void doorArrivalFarFromThePinIsRecordedUnverifiedWithItsDistance() throws Exception {
        assignment.setDeliveryStatus(DeliveryStatus.OUT_FOR_DELIVERY);
        when(values.get(LiveRiderLocation.redisKey(orderId.toString()))).thenReturn(mapper.writeValueAsString(
                new LiveRiderLocation(driverId.toString(), orderId.toString(), "BLR", 12.9406, 77.6244, NOW.toEpochMilli() - 2_000)));

        assertDoesNotThrow(() -> strategy(AtDropStateStrategy.class)
                .handleStatusUpdate(driverId, orderId, DeliveryStatus.AT_DROP, null, null, false));

        JsonNode event = published(EventType.ORDER_AT_DROP);
        assertFalse(event.path("verified").asBoolean(true));
        assertTrue(event.path("distanceMetres").asInt() > 500, event.toString());
        assertEquals(NOW.toString(), event.path("occurredAt").asText());
        assertEquals(NOW, assignment.getAtDropAt());
        assertEquals(Boolean.FALSE, assignment.getAtDropVerified());
        assertEquals(DeliveryStatus.AT_DROP, assignment.getDeliveryStatus());
    }

    @Test
    void verifiedDoorArrivalIsRecordedVerified() throws Exception {
        assignment.setDeliveryStatus(DeliveryStatus.OUT_FOR_DELIVERY);
        when(values.get(LiveRiderLocation.redisKey(orderId.toString()))).thenReturn(mapper.writeValueAsString(
                new LiveRiderLocation(driverId.toString(), orderId.toString(), "BLR", 12.9353, 77.6244, NOW.toEpochMilli() - 2_000)));

        strategy(AtDropStateStrategy.class).handleStatusUpdate(driverId, orderId, DeliveryStatus.AT_DROP, null, null, false);

        assertTrue(published(EventType.ORDER_AT_DROP).path("verified").asBoolean());
        assertEquals(Boolean.TRUE, assignment.getAtDropVerified());
    }

    @Test
    void aRepeatedTapKeepsTheFirstArrival() throws Exception {
        assignment.setDeliveryStatus(DeliveryStatus.OUT_FOR_DELIVERY);
        assignment.recordArrival(DeliveryStatus.AT_DROP, NOW.minusSeconds(120), true);

        strategy(AtDropStateStrategy.class).handleStatusUpdate(driverId, orderId, DeliveryStatus.AT_DROP, null, null, false);

        assertEquals(NOW.minusSeconds(120), assignment.getAtDropAt());
        assertEquals(Boolean.TRUE, assignment.getAtDropVerified());
    }

    @Test
    void doorTapBeforePickupIsRefused() {
        assignment.setDeliveryStatus(DeliveryStatus.AT_RESTAURANT);
        assertThrows(IllegalArgumentException.class, () -> strategy(AtDropStateStrategy.class)
                .handleStatusUpdate(driverId, orderId, DeliveryStatus.AT_DROP, null, null, false));
        verifyNoInteractions(outbox);
    }
}
