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
@EmbeddedKafka(partitions = 1, topics = {"platform.logistics.dispatch", "order-events"})
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

    /** Mirrors LogisticsDispatchService.dispatchNearestDriver's request map and key. */
    public void fireLogisticsDispatch() throws Exception {
        java.util.UUID orderId = java.util.UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
        java.util.Map<String, Object> dispatchRequest = new java.util.HashMap<>();
        dispatchRequest.put("orderId", orderId.toString());
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

    public void fireOrderStatusUpdated() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode payloadNode = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        payloadNode.put("eventType", "ORDER_STATUS_UPDATED");
        payloadNode.put("orderId", "3f2504e0-4f89-41d3-9a0c-0305e82c3301");
        payloadNode.put("status", "OUT_FOR_DELIVERY");
        payloadNode.put("pickupOtp", "1234");
        payloadNode.put("deliveryOtp", "5678");

        com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent =
                com.fooddelivery.common.outbox.entity.OutboxEventEntity.builder()
                        .id(java.util.UUID.randomUUID())
                        .aggregateType(com.fooddelivery.common.constants.AggregateType.ORDER)
                        .aggregateId("3f2504e0-4f89-41d3-9a0c-0305e82c3301")
                        .eventType(com.fooddelivery.common.constants.EventType.ORDER_STATUS_UPDATED)
                        .payload(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payloadNode))
                        .createdAt(java.time.LocalDateTime.now())
                        .build();

        com.fooddelivery.common.outbox.repository.OutboxEventRepository repo =
                org.mockito.Mockito.mock(com.fooddelivery.common.outbox.repository.OutboxEventRepository.class);
        org.mockito.Mockito.when(repo.findTop100ByStatusInOrderByCreatedAtAsc(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new java.util.ArrayList<>(java.util.List.of(outboxEvent)));
        new com.fooddelivery.common.outbox.service.OutboxProcessor(repo, kafkaTemplate, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).processOutboxEvents();
    }

}
