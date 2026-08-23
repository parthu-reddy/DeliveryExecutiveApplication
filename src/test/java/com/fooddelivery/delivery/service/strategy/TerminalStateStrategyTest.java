package com.fooddelivery.delivery.service.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TerminalStateStrategyTest {

    @InjectMocks
    private TerminalStateStrategy strategy;

    @Test
    void testGetEventTypes() {
        assertThat(strategy.getEventTypes()).containsExactlyInAnyOrder(
            "DRIVER_ASSIGNED", "DISPATCH_FAILED", "ORDER_CANCELLED", "DELIVERY_FAILED",
            "ORDER_CANCELLED_BY_RESTAURANT", "ORDER_CANCELLED_BY_CUSTOMER", "ORDER_CANCELLED_BY_ADMIN",
            "ORDER_REJECTED", "ORDER_DELAY_REJECTED", "MANUAL_INTERVENTION_REQUIRED"
        );
    }
}
