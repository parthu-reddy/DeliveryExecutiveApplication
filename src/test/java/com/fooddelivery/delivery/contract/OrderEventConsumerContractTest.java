package com.fooddelivery.delivery.contract;

import com.fooddelivery.common.contract.KafkaStubMessageSender;
import com.fooddelivery.delivery.service.OrderEventConsumer;
import com.fooddelivery.common.repository.IIdempotencyKeyRepository;
import com.fooddelivery.delivery.service.strategy.DeliveryEventStrategy;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.contract.stubrunner.StubTrigger;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.contract.verifier.messaging.MessageVerifierSender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.messaging.Message;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

@SpringBootTest(classes = OrderEventConsumerContractTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "spring.kafka.admin.auto-create=true", "spring.kafka.admin.operation-timeout=60s", "spring.kafka.admin.fail-fast=true", "spring.kafka.admin.properties.request.timeout.ms=60000", "spring.kafka.admin.properties.default.api.timeout.ms=60000"})
@ActiveProfiles("contract-test")
@AutoConfigureStubRunner(ids = "com.fooddelivery:order-service:+:stubs")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_CLASS)
// Topics come from KafkaAdmin (embeddedKafkaTopics) under the spring.kafka.admin deadline set above, with broker
// auto-creation off so an undeclared topic fails. @EmbeddedKafka(topics) waits a fixed 10 s: spring-kafka-test 3.2.0's
// EmbeddedKafkaContextCustomizer ignores adminTimeout (BV-T2/BV-T3, 2026-11-12).
@EmbeddedKafka(partitions = 1, brokerProperties = "auto.create.topics.enable=false")
class OrderEventConsumerContractTest {

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    
    @Import(OrderEventConsumer.class)
    static class TestConfig {
        /** Every topic this context listens on or publishes to; KafkaAdmin creates them before the listeners start. */
        @org.springframework.context.annotation.Bean
        org.springframework.kafka.core.KafkaAdmin.NewTopics embeddedKafkaTopics() {
            return new org.springframework.kafka.core.KafkaAdmin.NewTopics(java.util.stream.Stream.of("order-events")
                    .map(name -> org.springframework.kafka.config.TopicBuilder.name(name).partitions(1).replicas(1).build())
                    .toArray(org.apache.kafka.clients.admin.NewTopic[]::new));
        }

        @Bean
        public MessageVerifierSender<Message<?>> kafkaStubMessageSender(KafkaTemplate<String, String> t) {
            return new KafkaStubMessageSender(t);
        }

        @Bean
        public TransactionTemplate transactionTemplate() {
            PlatformTransactionManager tm = Mockito.mock(PlatformTransactionManager.class);
            Mockito.when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            return new TransactionTemplate(tm);
        }

        @Bean
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
        
        @Bean
        public DeliveryEventStrategy[] strategies() {
            return new DeliveryEventStrategy[0];
        }
        
        @Bean
        public com.fooddelivery.common.event.EventBinder eventBinder(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            return new com.fooddelivery.common.event.EventBinder(objectMapper, jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        }
    }

    @MockBean
    private IIdempotencyKeyRepository idempotencyKeyRepository;

    @Test
    void consumesOrderCreatedWithoutException() {
        Mockito.when(idempotencyKeyRepository.existsById(anyString())).thenReturn(false);

        stubTrigger.trigger("order_created");

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(idempotencyKeyRepository).tryClaim(anyString());
        });
    }

    @Autowired
    private StubTrigger stubTrigger;
}
