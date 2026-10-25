package com.fooddelivery.delivery.contract;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.verifier.messaging.boot.AutoConfigureMessageVerifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = BaseMessagingClass.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {"spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"})
@org.springframework.test.context.ActiveProfiles("contract-test")
@AutoConfigureMessageVerifier
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_CLASS)
@EmbeddedKafka(adminTimeout = 60, partitions = 1, topics = {"platform.logistics.dispatch", "order-events", "delivery-partner-events"})
public abstract class BaseMessagingClass {

    @MockBean
    private StringRedisTemplate redisTemplate;


    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    static class TestConfig {
        @Bean
        public KafkaMessageVerifier kafkaMessageVerifier() {
            return new KafkaMessageVerifier();
        }
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers", "localhost:9092"));
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper mapper;

    /** Mirrors LogisticsDispatchService.dispatchNearestDriver's request map and key. */
    public void fireLogisticsDispatch() throws Exception {
        java.util.UUID orderId = java.util.UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
        java.util.Map<String, Object> dispatchRequest = new java.util.HashMap<>();
        dispatchRequest.put("orderId", orderId.toString());
        dispatchRequest.put("dispatchCityId", "BLR");
        dispatchRequest.put("fleetSearchRadiusKm", 5.0);
        dispatchRequest.put("restaurantLat", 12.971598);
        dispatchRequest.put("restaurantLng", 77.594562);
        dispatchRequest.put("deliveryLat", 12.935242);
        dispatchRequest.put("deliveryLng", 77.624400);
        dispatchRequest.put("deliveryAddress", "221B Baker Street, Bangalore");
        dispatchRequest.put("excludedDriverIds", java.util.Collections.emptyList());
        // mirror LogisticsDispatchService: a Message carrying the eventId header consumers
        // now key their idempotency on, not the headerless (topic, key, payload) overload
        org.springframework.messaging.Message<String> message =
                org.springframework.messaging.support.MessageBuilder
                    .withPayload(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dispatchRequest))
                    .setHeader(org.springframework.kafka.support.KafkaHeaders.TOPIC,
                            com.fooddelivery.common.constants.KafkaConstants.TOPIC_LOGISTICS_DISPATCH)
                    .setHeader(org.springframework.kafka.support.KafkaHeaders.KEY, orderId.toString())
                    .setHeader("eventId", "0f1a5cb3-2b6d-5a1e-9c47-8e3f6d2a1b04")
                    .build();
        kafkaTemplate.send(message);
    }

    @Autowired
    private KafkaMessageVerifier messageVerifier;

    /** Exercise the production transition serializer and actual outbox topic/header routing. */
    public void fireDeliveryApplicationStatusChanged() throws Exception {
        var repo = org.mockito.Mockito.mock(com.fooddelivery.common.outbox.repository.OutboxEventRepository.class);
        var emitted = new java.util.ArrayList<com.fooddelivery.common.outbox.entity.OutboxEventEntity>();
        org.mockito.Mockito.when(repo.save(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            com.fooddelivery.common.outbox.entity.OutboxEventEntity event = call.getArgument(0); emitted.add(event); return event;
        });
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            var events = new com.fooddelivery.common.service.ApplicationEvents(repo, mapper,
                    org.mockito.Mockito.mock(com.fooddelivery.common.audit.AuditTrail.class),
                    org.mockito.Mockito.mock(com.fooddelivery.common.service.NotificationRouterService.class),
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
            var id = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
            events.delivery(id, com.fooddelivery.common.enums.ApplicationStatus.SUBMITTED, null, 3,
                    java.time.Instant.parse("2026-10-04T00:00:00Z"), id, "USER",
                    com.fooddelivery.common.enums.AuditAction.APPLICATION_SUBMITTED, java.util.List.of(id));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        org.mockito.Mockito.when(repo.findTop100ByStatusInOrderByCreatedAtAsc(org.mockito.ArgumentMatchers.anyList())).thenReturn(emitted);
        new com.fooddelivery.common.outbox.service.OutboxProcessor(repo, kafkaTemplate,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).processOutboxEvents();
    }

    public void fireTelemetryEvent() {
        String payload = """
{
  "driverId": "4f4a4e37-6ca5-5598-94f1-43ef1628f631",
  "lat": 12.971598,
  "lng": 77.594562,
  "orderId": "7a1d5e90-3c22-4b6f-8a11-9d4c2e77b501",
  "speedKmh": 18.5,
  "isMockLocation": false,
  "timestampMs": 1699999999999
}""";
        messageVerifier.publishDirect("tracking:order:7a1d5e90-3c22-4b6f-8a11-9d4c2e77b501", payload);
    }

    private static final java.util.UUID ORDER_ID = java.util.UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
    private static final java.util.UUID DRIVER_ID = java.util.UUID.fromString("4f4a4e37-6ca5-5598-94f1-43ef1628f631");
    /** Every milestone below is stamped with this instant, so the contracts can state occurredAt exactly. */
    private static final java.time.Clock CLOCK =
            java.time.Clock.fixed(java.time.Instant.parse("2026-10-25T09:30:00Z"), java.time.ZoneOffset.UTC);

    /** ORDER_AT_RESTAURANT as AtRestaurantStateStrategy writes it to the outbox. */
    public void fireOrderAtRestaurant() throws Exception {
        publishStep(new com.fooddelivery.delivery.service.state.order.AtRestaurantStateStrategy(
                riderAt(OUTLET_LAT + 0.0003, OUTLET_LNG), mapper,
                org.mockito.Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class),
                org.mockito.Mockito.mock(com.fooddelivery.common.outbox.repository.OutboxEventRepository.class),
                org.mockito.Mockito.mock(com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository.class),
                org.mockito.Mockito.mock(com.fooddelivery.delivery.service.LogisticsDispatchService.class),
                org.mockito.Mockito.mock(com.fooddelivery.delivery.repository.OrderAssignmentRepository.class), CLOCK),
                com.fooddelivery.common.enums.DeliveryStatus.AT_RESTAURANT);
    }

    /**
     * ORDER_AT_DROP as AtDropStateStrategy writes it to the outbox: the rider's live position is about 600 m from the pin,
     * so the tap is recorded unverified, with its distance (D16).
     */
    public void fireOrderAtDrop() throws Exception {
        publishStep(new com.fooddelivery.delivery.service.state.order.AtDropStateStrategy(
                riderAt(DROP_LAT + 0.0054, DROP_LNG), mapper,
                org.mockito.Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class),
                org.mockito.Mockito.mock(com.fooddelivery.common.outbox.repository.OutboxEventRepository.class),
                org.mockito.Mockito.mock(com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository.class),
                org.mockito.Mockito.mock(com.fooddelivery.delivery.service.LogisticsDispatchService.class),
                org.mockito.Mockito.mock(com.fooddelivery.delivery.repository.OrderAssignmentRepository.class), CLOCK),
                com.fooddelivery.common.enums.DeliveryStatus.AT_DROP);
    }

    /** ORDER_STATUS_UPDATED (OUT_FOR_DELIVERY) as OutForDeliveryStateStrategy writes it to the outbox. */
    public void fireOrderStatusUpdated() throws Exception {
        publishStep(new com.fooddelivery.delivery.service.state.order.OutForDeliveryStateStrategy(
                org.mockito.Mockito.mock(StringRedisTemplate.class), mapper,
                org.mockito.Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class),
                org.mockito.Mockito.mock(com.fooddelivery.common.outbox.repository.OutboxEventRepository.class),
                org.mockito.Mockito.mock(com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository.class),
                org.mockito.Mockito.mock(com.fooddelivery.delivery.service.LogisticsDispatchService.class),
                org.mockito.Mockito.mock(com.fooddelivery.delivery.repository.OrderAssignmentRepository.class), CLOCK),
                com.fooddelivery.common.enums.DeliveryStatus.OUT_FOR_DELIVERY);
    }

    /** ORDER_DELIVERED as DeliveredStateStrategy writes it to the outbox. */
    public void fireOrderDelivered() throws Exception {
        publishStep(new com.fooddelivery.delivery.service.state.order.DeliveredStateStrategy(
                org.mockito.Mockito.mock(StringRedisTemplate.class), mapper,
                org.mockito.Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class),
                org.mockito.Mockito.mock(com.fooddelivery.common.outbox.repository.OutboxEventRepository.class),
                org.mockito.Mockito.mock(com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository.class),
                org.mockito.Mockito.mock(com.fooddelivery.delivery.service.LogisticsDispatchService.class),
                org.mockito.Mockito.mock(com.fooddelivery.delivery.repository.OrderAssignmentRepository.class), CLOCK),
                com.fooddelivery.common.enums.DeliveryStatus.DELIVERED);
    }

    /** DRIVER_ASSIGNED as OrderAssignmentService builds it when a rider accepts the ping. */
    public void fireDriverAssigned() throws Exception {
        var service = new com.fooddelivery.delivery.service.OrderAssignmentService(
                org.mockito.Mockito.mock(StringRedisTemplate.class),
                org.mockito.Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class),
                org.mockito.Mockito.mock(com.fooddelivery.common.outbox.repository.OutboxEventRepository.class),
                org.mockito.Mockito.mock(com.fooddelivery.delivery.service.OutboxEventHelper.class),
                org.mockito.Mockito.mock(com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository.class),
                org.mockito.Mockito.mock(com.fooddelivery.delivery.service.LogisticsDispatchService.class),
                org.mockito.Mockito.mock(com.fooddelivery.delivery.repository.OrderAssignmentRepository.class), mapper,
                org.mockito.Mockito.mock(com.fooddelivery.delivery.client.OrderServiceClient.class), CLOCK);
        publish(com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED,
                service.assignedEvent(ORDER_ID, DRIVER_ID, "Ravi Kumar", new org.springframework.data.geo.Point(77.5801, 12.9612)));
    }

    private static final double OUTLET_LAT = 12.971598, OUTLET_LNG = 77.594562, DROP_LAT = 12.935242, DROP_LNG = 77.624400;

    /** A Redis whose live position for ORDER_ID is this rider at (lat, lng), seen 5 s before CLOCK: fresh. */
    private StringRedisTemplate riderAt(double lat, double lng) throws Exception {
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> values =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(redis.opsForValue()).thenReturn(values);
        org.mockito.Mockito.when(values.get(com.fooddelivery.common.dto.maps.LiveRiderLocation.redisKey(ORDER_ID.toString())))
                .thenReturn(mapper.writeValueAsString(new com.fooddelivery.common.dto.maps.LiveRiderLocation(
                        DRIVER_ID.toString(), ORDER_ID.toString(), "BLR", lat, lng, CLOCK.millis() - 5_000)));
        return redis;
    }

    /** The step as the strategy computes it for this assignment -- time, geo check and event -- published as written. */
    private void publishStep(com.fooddelivery.delivery.service.state.order.AbstractDeliveryOrderState strategy,
                             com.fooddelivery.common.enums.DeliveryStatus status) throws Exception {
        com.fooddelivery.delivery.entity.OrderAssignment assignment = com.fooddelivery.delivery.entity.OrderAssignment.builder()
                .orderId(ORDER_ID).driverId(DRIVER_ID).state(com.fooddelivery.delivery.entity.OrderAssignment.State.ASSIGNED)
                .pickupLat(OUTLET_LAT).pickupLng(OUTLET_LNG).dropLat(DROP_LAT).dropLng(DROP_LNG).build();
        publish(strategy.getEventType(), strategy.step(assignment, DRIVER_ID, status, "567890").event());
    }

    /** One outbox row, serialized by the Boot ObjectMapper as production does, through the real OutboxProcessor. */
    private void publish(com.fooddelivery.common.constants.EventType eventType, Object event) throws Exception {
        com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent =
                com.fooddelivery.common.outbox.entity.OutboxEventEntity.builder()
                        .id(java.util.UUID.randomUUID())
                        .aggregateType(com.fooddelivery.common.constants.AggregateType.ORDER)
                        .aggregateId(ORDER_ID.toString())
                        .eventType(eventType)
                        .payload(mapper.writeValueAsString(event))
                        .createdAt(java.time.Instant.now())
                        .build();
        com.fooddelivery.common.outbox.repository.OutboxEventRepository repo =
                org.mockito.Mockito.mock(com.fooddelivery.common.outbox.repository.OutboxEventRepository.class);
        org.mockito.Mockito.when(repo.findTop100ByStatusInOrderByCreatedAtAsc(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new java.util.ArrayList<>(java.util.List.of(outboxEvent)));
        new com.fooddelivery.common.outbox.service.OutboxProcessor(repo, kafkaTemplate, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).processOutboxEvents();
    }

}
