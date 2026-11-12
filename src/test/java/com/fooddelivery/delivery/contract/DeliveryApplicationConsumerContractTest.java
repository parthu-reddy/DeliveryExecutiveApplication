package com.fooddelivery.delivery.contract;

import com.fooddelivery.common.contract.KafkaStubMessageSender;
import com.fooddelivery.common.event.EventBinder;
import com.fooddelivery.delivery.service.DeliveryApplicationEventListener;
import com.fooddelivery.delivery.service.DeliveryApplicationService;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.contract.stubrunner.StubTrigger;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.contract.verifier.messaging.MessageVerifierSender;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.messaging.Message;
import org.springframework.test.context.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verify;

/** The producer's current stub must pass the actual consumer's typed binding. */
@SpringBootTest(classes=DeliveryApplicationConsumerContractTest.Config.class,
        webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"spring.kafka.admin.auto-create=true", "spring.kafka.admin.operation-timeout=60s", "spring.kafka.admin.fail-fast=true", "spring.kafka.admin.properties.request.timeout.ms=60000", "spring.kafka.admin.properties.default.api.timeout.ms=60000",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"})
@ActiveProfiles("contract-test")
@AutoConfigureStubRunner(ids="com.fooddelivery:delivery-executive-application:+:stubs")
// Topics come from KafkaAdmin (embeddedKafkaTopics) under the spring.kafka.admin deadline set above, with broker
// auto-creation off so an undeclared topic fails. @EmbeddedKafka(topics) waits a fixed 10 s: spring-kafka-test 3.2.0's
// EmbeddedKafkaContextCustomizer ignores adminTimeout (BV-T2/BV-T3, 2026-11-12).
@EmbeddedKafka(partitions = 1, brokerProperties = "auto.create.topics.enable=false")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_CLASS)
class DeliveryApplicationConsumerContractTest {
    @org.springframework.boot.SpringBootConfiguration @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @Import(DeliveryApplicationEventListener.class)
    static class Config {
        /** Every topic this context listens on or publishes to; KafkaAdmin creates them before the listeners start. */
        @org.springframework.context.annotation.Bean
        org.springframework.kafka.core.KafkaAdmin.NewTopics embeddedKafkaTopics() {
            return new org.springframework.kafka.core.KafkaAdmin.NewTopics(java.util.stream.Stream.of("delivery-partner-events")
                    .map(name -> org.springframework.kafka.config.TopicBuilder.name(name).partitions(1).replicas(1).build())
                    .toArray(org.apache.kafka.clients.admin.NewTopic[]::new));
        }

        @Bean EventBinder binder(com.fasterxml.jackson.databind.ObjectMapper mapper) {
            return new EventBinder(mapper,jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        }
        @Bean MessageVerifierSender<Message<?>> sender(KafkaTemplate<String,String> kafka) {return new KafkaStubMessageSender(kafka);}
    }
    @DynamicPropertySource static void kafka(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers",() -> System.getProperty("spring.embedded.kafka.brokers"));
    }
    @MockBean DeliveryApplicationService applications;
    @Autowired StubTrigger trigger;
    @Test void submittedProducerEventStartsChecksForItsExactApplicationRevision() {
        trigger.trigger("delivery_application_status_changed");
        await().atMost(15,TimeUnit.SECONDS).untilAsserted(() -> verify(applications)
                .processChecks(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),3L));
    }
}
