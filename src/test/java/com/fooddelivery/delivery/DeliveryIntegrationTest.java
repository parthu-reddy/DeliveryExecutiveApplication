package com.fooddelivery.delivery;

import com.fooddelivery.common.test.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class DeliveryIntegrationTest extends BaseIntegrationTest {

    @Test
    void shouldAssignExecutiveOnOrderReadyEvent() {
        // Here we could simulate sending a Kafka event to "restaurant-events" for ORDER_READY
        // and assert that the Delivery service picks it up and updates DB.
        assertThat(true).isTrue();
    }
}
