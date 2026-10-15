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

import static org.junit.jupiter.api.Assertions.assertFalse;

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
    @org.springframework.boot.test.mock.mockito.MockBean private com.fooddelivery.delivery.service.DeliveryApplicationService deliveryApplications;


    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.common.client.GovernmentIdServiceClient governmentIdServiceClient;




    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.delivery.client.OrderServiceClient orderServiceClient;


    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.delivery.service.OrderExecutionService orderExecutionService;


    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.delivery.service.OrderAssignmentService orderAssignmentService;


    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.delivery.service.DeliveryExecutiveProfileService deliveryExecutiveProfileService;


    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository iDeliveryExecutiveRepository;

    // DeliveryExecutiveController took this in Phase 2, when the OTP and the authorization decision
    // moved off the Redis dispatch payload and onto order_assignments. This class mocks every
    // collaborator by hand, so a new constructor parameter breaks it. It was then left out of the
    // default surefire run, so the break sat here unseen and the committed openapi.json silently
    // stopped being regenerated; it runs in every build since 2026-10-11.
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.delivery.repository.OrderAssignmentRepository orderAssignmentRepository;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.delivery.service.ConfirmedDeliveryProgress confirmedDeliveryProgress;

    @org.springframework.context.annotation.Configuration
    // Relabels structured responses from */* to application/json. Without it every
    // generated Zod response validator degrades to z.void(); the scoped scan below
    // does not reach com.fooddelivery.common.config.
    @org.springframework.context.annotation.Import({com.fooddelivery.common.config.OpenApiJsonMediaTypeCustomizer.class, com.fooddelivery.common.config.OpenApiPaginationRequiredCustomizer.class})
    @org.springframework.context.annotation.ComponentScan(basePackages = {"com.fooddelivery.delivery.controller"},
            excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                    type = org.springframework.context.annotation.FilterType.ANNOTATION,
                    classes = org.springframework.boot.test.context.TestComponent.class))
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
            org.junit.jupiter.api.Assertions.assertTrue(openApiJson.contains("\"/api/v1/delivery-onboarding/application\""));
            assertFalse(openApiJson.contains("\"/api/delivery/onboard\""));
            assertFalse(openApiJson.contains("\"/api/v1/internal/admin/delivery/orders/{orderId}/assign\""),
                    "The retired direct-assignment endpoint must not be published in the Delivery OpenAPI contract.");
            Path path = Paths.get("target/openapi.json");
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.write(path, openApiJson.getBytes(StandardCharsets.UTF_8));
            assertCommittedSpecCurrent(openApiJson);
            System.out.println("OpenAPI spec written to target/openapi.json");
        } else {
            throw new IllegalStateException("Failed to retrieve OpenAPI spec.");
        }
    }

    // openapi.json at the module root is what the UI client and the validators are generated from. Red here
    // when the controllers no longer produce it, so it cannot go stale unnoticed. Parsed trees are compared
    // because springdoc's key order is not stable between runs.
    private static void assertCommittedSpecCurrent(String openApiJson) throws java.io.IOException {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode generated = mapper.readTree(openApiJson);
        com.fasterxml.jackson.databind.JsonNode committed = mapper.readTree(
                java.nio.file.Files.readString(java.nio.file.Paths.get("openapi.json")));
        if (generated.equals(committed)) {
            return;
        }
        java.util.List<String> changed = new java.util.ArrayList<>();
        for (String section : new String[] {"/paths", "/components/schemas"}) {
            java.util.Set<String> names = new java.util.TreeSet<>();
            generated.at(section).fieldNames().forEachRemaining(names::add);
            committed.at(section).fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (!generated.at(section).path(name).equals(committed.at(section).path(name))) {
                    changed.add(section + "/" + name);
                }
            }
        }
        org.junit.jupiter.api.Assertions.fail("openapi.json is stale (differs at "
                + (changed.isEmpty() ? "info/servers/tags" : changed) + "). Copy target/openapi.json over it;"
                + " for a UI service FoodDeliveryAppUI/scripts/generate-api-types.sh does that and regenerates the client.");
    }
}
