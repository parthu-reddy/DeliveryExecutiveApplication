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
        webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"})
@ActiveProfiles("contract-test")
@AutoConfigureStubRunner(ids="com.fooddelivery:delivery-executive-application:+:stubs")
@EmbeddedKafka(adminTimeout=60,partitions=1,topics="delivery-partner-events")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_CLASS)
class DeliveryApplicationConsumerContractTest {
    @org.springframework.boot.SpringBootConfiguration @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @Import(DeliveryApplicationEventListener.class)
    static class Config {
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
