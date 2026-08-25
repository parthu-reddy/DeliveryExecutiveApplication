package com.fooddelivery.contract;

import com.fooddelivery.common.client.GovernmentIdServiceClient;
import com.fooddelivery.common.client.GovernmentIdServiceClient.VerificationSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.mockito.Mockito;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = GovIdContractConsumerTest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureStubRunner(
        stubsMode = StubRunnerProperties.StubsMode.LOCAL,
        ids = {"com.fooddelivery:government-id-validation-service:+:stubs:8094"}
)
@ActiveProfiles("contract-test")
public class GovIdContractConsumerTest {

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class
    })
    @EnableFeignClients(basePackages = {"com.fooddelivery.executive.client", "com.fooddelivery.common.client"})
    static class TestConfig {
        @Bean
        public com.fooddelivery.common.client.GovernmentIdServiceClientFallback governmentIdServiceClientFallback() {
            return Mockito.mock(com.fooddelivery.common.client.GovernmentIdServiceClientFallback.class);
        }
    }

    @MockBean
    private org.springframework.kafka.core.KafkaTemplate kafkaTemplate;
    
    // Delivery Executive App specific mock beans if any are eagerly loaded (e.g., from Feign config)
    @MockBean
    private com.fooddelivery.common.client.PaymentServiceClientFallback paymentServiceClientFallback;

    @Autowired
    private GovernmentIdServiceClient governmentIdServiceClient;

    @Test
    public void shouldGetVerificationSummary() {
        UUID execId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

        VerificationSummary summary = governmentIdServiceClient.getVerificationSummary(execId);
        
        assertNotNull(summary);
        assertTrue(summary.allDocsApproved());
    }
}
