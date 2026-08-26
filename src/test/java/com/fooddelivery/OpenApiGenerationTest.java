package com.fooddelivery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

import javax.sql.DataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;

@SpringBootTest(classes = OpenApiGenerationTest.TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb_openapi;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "springdoc.writer-with-default-pretty-printer=true",
    "spring.cloud.config.enabled=false",
    "eureka.client.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:9092", "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.fail-fast=true",
    "spring.flyway.enabled=false",    "spring.sql.init.mode=never",
    "spring.main.allow-bean-definition-overriding=true",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.redis.enabled=false", 
    "management.health.redis.enabled=false",    "jwt.secret=dummy",
    "jwt.expiration=3600000",
    "google.maps.api.key=dummy",
    "stripe.api.key=dummy",
    "stripe.webhook.secret=dummy",
    "platform.webhook.secret=dummy",
    "razorpay.api.key=dummy",
    "razorpay.api.secret=dummy",
    "aws.accessKeyId=dummy",
    "aws.secretKey=dummy",
    "aws.s3.bucket=dummy",
    "aws.region=dummy",
    "twilio.account_sid=dummy",
    "twilio.auth_token=dummy",
    "twilio.phone_number=dummy",
    "brevo.api.key=dummy",
    "cashfree.client.id=dummy",
    "cashfree.client.secret=dummy",
    "exotel.account.sid=dummy",
    "exotel.api.key=dummy",
    "exotel.api.token=dummy",
    "gupshup.api.key=dummy",
    "gupshup.source.number=dummy",
    "olamaps.api.key=dummy",
    "platform.default-currency=USD",
    "platform.webhook.base-url=http://localhost",
    "r2.access-key=dummy",
    "r2.bucket-name=dummy",
    "r2.endpoint=https://dummy.com",
    "r2.public-url=dummy",
    "r2.secret-key=dummy",
    "razorpay.key.id=dummy",
    "razorpay.key.secret=dummy",
    "razorpay.webhook.secret=dummy",
    "spring.kafka.consumer.group-id=test",
    "vyapargateway.api.key=dummy",
    "vyapargateway.webhook.secret=dummy"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
@AutoConfigureWebTestClient
public class OpenApiGenerationTest {

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.common.client.MapsServiceClient mapsServiceClient;


    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.delivery.service.OnboardingOrchestratorService onboardingOrchestratorService;


    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.common.client.GovernmentIdServiceClient governmentIdServiceClient;


    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.telemetry.service.TelemetryIngestionService telemetryIngestionService;


    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.delivery.client.CustomerServiceClient customerServiceClient;


    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.delivery.service.OrderExecutionService orderExecutionService;


    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.delivery.service.OrderAssignmentService orderAssignmentService;


    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.delivery.service.DeliveryExecutiveProfileService deliveryExecutiveProfileService;


    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository iDeliveryExecutiveRepository;

    @org.springframework.context.annotation.Configuration
    @org.springframework.context.annotation.ComponentScan(basePackages = {"com.fooddelivery.delivery.controller"})
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(excludeName = {"org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration", "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration", "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration", "org.springframework.boot.actuate.autoconfigure.security.reactive.ManagementReactiveSecurityAutoConfiguration", "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration"})
    static class TestApp {
    }


    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

    @org.springframework.boot.test.mock.mockito.MockBean(name = "kafkaTemplate")

    private org.springframework.kafka.core.KafkaTemplate kafkaTemplate;

    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.data.redis.connection.ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;


    // Mock critical infrastructure so the context loads
    
    
    @MockBean
    private RedisConnectionFactory redisConnectionFactory;
    @MockBean
    private com.fooddelivery.common.service.RateLimitingService rateLimitingService;
    @MockBean
    private org.springframework.data.redis.listener.RedisMessageListenerContainer redisMessageListenerContainer;

    @Autowired(required = false)
    private MockMvc mockMvc;

    @Autowired(required = false)
    private WebTestClient webTestClient;

    @Autowired
    private ApplicationContext context;

    @Test
    public void generateOpenApi() throws Exception {
        String openApiJson = null;

        // Try MockMvc first (for WebMVC)
        if (mockMvc != null) {
            System.out.println("--- Spring Beans ---");
            for (String beanName : context.getBeanDefinitionNames()) {
                if (beanName.toLowerCase().contains("springdoc") || beanName.toLowerCase().contains("openapi")) {
                    System.out.println("Bean: " + beanName);
                }
            }
            System.out.println("--------------------");

            var mvcResult = mockMvc.perform(MockMvcRequestBuilders.get("/v3/api-docs")).andReturn();
            System.out.println("MockMvc Status: " + mvcResult.getResponse().getStatus());
            System.out.println("MockMvc Async: " + mvcResult.getRequest().isAsyncStarted());
            System.out.println("MockMvc Content Type: " + mvcResult.getResponse().getContentType());
            System.out.println("MockMvc Content Length: " + mvcResult.getResponse().getContentLength());
            openApiJson = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
            System.out.println("MockMvc Content snippet: " + (openApiJson.length() > 50 ? openApiJson.substring(0, 50) : openApiJson));
        } else if (webTestClient != null) {
            // Try WebTestClient (for WebFlux)
            byte[] responseBody = webTestClient.get().uri("/v3/api-docs").exchange()
                    .expectStatus().isOk()
                    .expectBody().returnResult().getResponseBody();
            if (responseBody != null) {
                openApiJson = new String(responseBody, StandardCharsets.UTF_8);
            }
        } else {
            throw new IllegalStateException("Neither MockMvc nor WebTestClient is available.");
        }

        if (openApiJson != null && !openApiJson.isEmpty()) {
            Path path = Paths.get("target/openapi.json");
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.write(path, openApiJson.getBytes(StandardCharsets.UTF_8));
            System.out.println("OpenAPI spec written to target/openapi.json");
        } else {
            throw new IllegalStateException("Failed to retrieve OpenAPI spec.");
        }
    }
}
