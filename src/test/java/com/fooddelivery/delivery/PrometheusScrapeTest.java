package com.fooddelivery.delivery;

import com.fooddelivery.common.test.BaseIntegrationTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * An unauthenticated GET of /actuator/prometheus on a real port, through every servlet filter and this service's own
 * security chain, answers 200 with metric samples: what Prometheus or an operator on the private network sees
 * (PrometheusRegistry_2026-10-17). It fails with 404 without the registry and with 401/403 when the chain refuses.
 *
 * <p>Boot setup copied from DeliveryValidationIntegrationTest.
 * No servlet filter is mocked (a mock ends the request with an empty 200) and no security auto-configuration is
 * excluded.
 */
@org.springframework.boot.test.context.SpringBootTest(classes = com.fooddelivery.DeliveryExecutiveApplication.class,
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.main.web-application-type=servlet"
})
@ActiveProfiles("contract-test")
// Boot's test support swaps every metrics exporter for a SimpleMeterRegistry; keep the real ones, as Dev runs them.
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability(tracing = false)
class PrometheusScrapeTest extends BaseIntegrationTest {

    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;
    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.data.redis.listener.RedisMessageListenerContainer redisMessageListenerContainer;

    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.kafka.core.KafkaTemplate kafkaTemplate;

    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.kafka.core.KafkaAdmin kafkaAdmin;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.common.service.RateLimitingService rateLimitingService;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @org.junit.jupiter.api.Test
    void anUnauthenticatedScrapeIsServed() throws Exception {
        com.fooddelivery.common.test.PrometheusScrape.assertServedAnonymously(port);
    }
}
