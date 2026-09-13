package com.fooddelivery.contract;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.cloud.openfeign.EnableFeignClients;

@ActiveProfiles("contract-test")
@SpringBootTest(classes = DeliveryExecutiveContractConsumerTest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    // Stub ids are Maven artifactIds; Feign resolves by spring.application.name. These two
    // differ for these services, so the stub must be registered under the name the client asks for.
    "stubrunner.idsToServiceIds.food-delivery-backend=customer-service"
})
@AutoConfigureStubRunner(ids = { "com.fooddelivery:food-delivery-backend:+:stubs" })
public class DeliveryExecutiveContractConsumerTest {


    @Autowired
    private com.fooddelivery.delivery.client.CustomerServiceClient customerServiceClient;

    @MockBean
    private com.fooddelivery.delivery.client.CustomerServiceClientFallback customerServiceClientFallback;


    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class
    })
    @EnableFeignClients(basePackages = "com.fooddelivery.delivery.client")
    static class TestConfig {
    }

    @Test
    public void testGetActiveOrdersForDriver() {
        com.fasterxml.jackson.databind.JsonNode response = customerServiceClient.getActiveOrdersForDriver(
                java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), 0, 10);

        assertNotNull(response);
        assertNotNull(response.get("content"));
        assertEquals(1, response.get("content").size());
        // OUT_FOR_DELIVERY is a DeliveryStatus, not an OrderStatus; Order carries both fields.
        assertEquals("OUT_FOR_DELIVERY", response.get("content").get(0).get("deliveryStatus").asText());
        assertEquals(1, response.get("totalElements").asInt());
    }

    @Test
    public void testGetOrderHistoryForDriver() {
        com.fasterxml.jackson.databind.JsonNode response = customerServiceClient.getOrderHistoryForDriver(
                java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), "2023-01-01", 0, 10);

        assertNotNull(response);
        assertNotNull(response.get("content"));
        assertEquals(1, response.get("content").size());
        // DELIVERED is a DeliveryStatus, not an OrderStatus.
        assertEquals("DELIVERED", response.get("content").get(0).get("deliveryStatus").asText());
    }

    @Test
    public void testGetUnassignedOrders() {
        java.util.List<com.fasterxml.jackson.databind.JsonNode> response = customerServiceClient.getUnassignedOrders();

        assertNotNull(response);
        assertEquals(1, response.size());
        assertEquals("PREPARING", response.get(0).get("status").asText());
    }

    @Test
    public void testGetDriverOrderMoney() {
        com.fooddelivery.common.dto.order.DriverOrderEarnings response = customerServiceClient.getOrderEarnings(
                java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));

        assertNotNull(response);
        assertEquals(new java.math.BigDecimal("25.0"), response.getGrossPayout());
        assertEquals(new java.math.BigDecimal("5.0"), response.getTaxes());
        assertEquals(new java.math.BigDecimal("20.0"), response.getNetPayout());
        assertEquals(new java.math.BigDecimal("5.0"), response.getPlatformBonus());
    }
}
