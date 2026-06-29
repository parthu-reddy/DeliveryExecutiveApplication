package com.fooddelivery.customer.controller;

import com.fooddelivery.AbstractIntegrationTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@Disabled("Requires Docker environment for Testcontainers")
class CustomerOrderE2ETest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void createOrder_ShouldReturnPaymentIntentAndOrderDetails() {
        UUID customerId = UUID.randomUUID();
        UUID restaurantId = UUID.randomUUID();
        UUID menuItemId = UUID.randomUUID();

        Map<String, Object> request = Map.of(
                "customerId", customerId.toString(),
                "restaurantId", restaurantId.toString(),
                "items", List.of(
                        Map.of(
                                "menuItemId", menuItemId.toString(),
                                "quantity", 2,
                                "price", 15.50
                        )
                )
        );

        given()
            .contentType(ContentType.JSON)
            .body(request)
        .when()
            .post("/api/v1/orders")
        .then()
            .statusCode(200)
            .body("success", is(true))
            .body("message", equalTo("Order created successfully"))
            .body("data.id", notNullValue())
            .body("data.status", equalTo("CREATED"))
            .body("data.paymentIntent", notNullValue())
            .body("data.paymentIntent.status", equalTo("CREATED"));
    }
}
