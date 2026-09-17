package com.fooddelivery.delivery.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.delivery.client.CustomerServiceClient;
import com.fooddelivery.delivery.service.OrderAssignmentService;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /orders/available} decides which prompt the rider app shows -- it is the endpoint the app
 * polls, not {@code /drivers/{id}/pings}.
 *
 * <p>It used to return the order whatever the deadline said. With an elapsed deadline it sent
 * {@code remainingPingSeconds=0}; with the deadline already cleaned up by the timeout poller it
 * sent no expiry field at all, and the app fell back to a fresh 60-second countdown. Either way the
 * rider got a prompt that ACCEPT_SCRIPT would refuse. The {@code driver:pending_ping} key
 * deliberately outlives the window, so its presence alone proves nothing.
 */
class AvailableOrdersPingWindowTest {

    private static final UUID DRIVER = UUID.fromString("4f4a4e37-6ca5-5598-94f1-43ef1628f631");
    private static final UUID ORDER = UUID.fromString("083610e8-d508-4b12-a78b-7c4e13cf3ade");

    private final CustomerServiceClient customerServiceClient = mock(CustomerServiceClient.class);
    private final OrderAssignmentService assignments = mock(OrderAssignmentService.class);
    private final DeliveryOrderController controller =
            new DeliveryOrderController(customerServiceClient, assignments);

    private final Principal principal = DRIVER::toString;

    private void givenAPendingPingExpiringAt(Long deadline) {
        when(assignments.getPendingPing(DRIVER)).thenReturn(ORDER.toString());
        when(assignments.getPingExpiration(ORDER)).thenReturn(deadline);
        JsonNode order = new ObjectMapper().createObjectNode().put("id", ORDER.toString());
        when(customerServiceClient.getUnassignedOrders()).thenReturn(List.of(order));
    }

    @Test
    void anElapsedDeadlineOffersNothing() {
        givenAPendingPingExpiringAt(System.currentTimeMillis() - 1_000);

        List<JsonNode> body = controller.getAvailableOrders(principal).getBody();

        assertThat(body).isEmpty();
        verify(customerServiceClient, never()).getUnassignedOrders();
    }

    @Test
    void aCleanedUpDeadlineOffersNothing() {
        // The timeout poller removed the ZSET entry, but driver:pending_ping is still alive.
        givenAPendingPingExpiringAt(null);

        assertThat(controller.getAvailableOrders(principal).getBody()).isEmpty();
    }

    @Test
    void aLivePingIsStillOfferedWithTheRealTimeRemaining() {
        givenAPendingPingExpiringAt(System.currentTimeMillis() + 30_000);

        List<JsonNode> body = controller.getAvailableOrders(principal).getBody();

        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("remainingPingSeconds").asLong())
                .describedAs("the rider's countdown must come from the real deadline")
                .isBetween(25L, 30L);
    }

    @Test
    void noPendingPingOffersNothing() {
        when(assignments.getPendingPing(DRIVER)).thenReturn(null);

        assertThat(controller.getAvailableOrders(principal).getBody()).isEmpty();
        verify(assignments, never()).getPingExpiration(any());
    }
}
