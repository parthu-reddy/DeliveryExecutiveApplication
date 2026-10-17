package com.fooddelivery.hop;

import com.fooddelivery.delivery.client.OrderServiceClient;
import com.fooddelivery.delivery.client.OrderServiceClientFallback;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.reset;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A 404 is an answer in delivery-service's breakers too, not only in the four split services: the rule now comes from
 * common-web for every service (ProductionReadiness PR-4). Through the real Feign client, Spring Cloud circuit breaker
 * and this service's own configuration, against a WireMock order-service. Before 2026-10-17 five 404s opened this
 * breaker and every later call failed fast.
 */
@SpringBootTest(classes = NotFoundKeepsBreakerClosedHopTest.HopConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.cloud.discovery.client.simple.instances.order-service[0].uri=http://localhost:${wiremock.server.port}",
            "eureka.client.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration"
        })
@AutoConfigureWireMock(port = 0)
class NotFoundKeepsBreakerClosedHopTest {

    private static final String BREAKER = "OrderServiceClientgetOrderDispatchDetailsUUID";
    private static final String DETAILS = "/api/v1/internal/orders/.*/dispatch-details";

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @EnableFeignClients(clients = OrderServiceClient.class)
    @Import(OrderServiceClientFallback.class)
    static class HopConfig {
    }

    @Autowired OrderServiceClient orders;
    @Autowired CircuitBreakerRegistry breakers;

    @BeforeEach
    void freshBreakerAndStubs() {
        reset();
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    private void answer(int status) {
        stubFor(get(urlPathMatching(DETAILS)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(status == 200 ? "{\"cityId\":\"blr\"}" : "{}")));
    }

    @Test
    void aBurstOf404sLeavesTheBreakerClosed() {
        answer(404);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> orders.getOrderDispatchDetails(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        }
        assertThat(breakers.circuitBreaker(BREAKER).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        answer(200);
        assertThat(orders.getOrderDispatchDetails(UUID.randomUUID())).containsEntry("cityId", "blr");
    }

    @Test
    void aBurstOf503sStillOpensIt() {
        answer(503);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> orders.getOrderDispatchDetails(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        }
        assertThat(breakers.circuitBreaker(BREAKER).getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
