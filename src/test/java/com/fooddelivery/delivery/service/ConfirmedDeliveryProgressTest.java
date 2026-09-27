package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.delivery.entity.OrderAssignment;
import com.fooddelivery.delivery.repository.OrderAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The customer service learns of each delivery step from this service's event a few seconds late.
 * These pin that the rider's active list never shows a step behind one this service accepted.
 */
class ConfirmedDeliveryProgressTest {

    private static final UUID RIDER = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private final ObjectMapper json = new ObjectMapper();
    private OrderAssignmentRepository repository;
    private ConfirmedDeliveryProgress progress;

    @BeforeEach
    void setUp() {
        repository = mock(OrderAssignmentRepository.class);
        progress = new ConfirmedDeliveryProgress(repository);
    }

    private JsonNode page(String status, String deliveryStatus) throws Exception {
        return json.readTree("{\"content\":[{\"id\":\"" + ORDER + "\",\"status\":\"" + status
                + "\",\"deliveryStatus\":\"" + deliveryStatus + "\"}]}");
    }

    private void assignment(UUID driver, DeliveryStatus confirmed) {
        OrderAssignment a = OrderAssignment.builder().orderId(ORDER).driverId(driver)
                .state(OrderAssignment.State.ASSIGNED).build();
        a.recordDeliveryStatus(confirmed);
        when(repository.findAllById(any())).thenReturn(List.of(a));
    }

    private String deliveryStatusOf(JsonNode page) {
        return page.get("content").get(0).get("deliveryStatus").asText();
    }

    @Test
    void aReloadAfterAnAcceptedPickupShowsDeliverToDoorNotThePickupForm() throws Exception {
        assignment(RIDER, DeliveryStatus.OUT_FOR_DELIVERY);
        JsonNode page = page("READY_FOR_PICKUP", "AT_RESTAURANT");

        progress.apply(page, RIDER);

        assertThat(deliveryStatusOf(page)).isEqualTo("OUT_FOR_DELIVERY");
    }

    @Test
    void aDeliveredOrderStillListedAsOutForDeliveryReadsDelivered() throws Exception {
        assignment(RIDER, DeliveryStatus.DELIVERED);
        JsonNode page = page("HANDED_OVER", "OUT_FOR_DELIVERY");

        progress.apply(page, RIDER);

        assertThat(deliveryStatusOf(page)).isEqualTo("DELIVERED");
    }

    @Test
    void neverMovesTheCustomerServicesAnswerBackwards() throws Exception {
        assignment(RIDER, DeliveryStatus.AT_RESTAURANT);
        JsonNode page = page("HANDED_OVER", "OUT_FOR_DELIVERY");

        progress.apply(page, RIDER);

        assertThat(deliveryStatusOf(page)).isEqualTo("OUT_FOR_DELIVERY");
    }

    @Test
    void aCancellationWins() throws Exception {
        assignment(RIDER, DeliveryStatus.OUT_FOR_DELIVERY);
        JsonNode page = page("CANCELLED_BY_RESTAURANT", "AT_RESTAURANT");

        progress.apply(page, RIDER);

        assertThat(deliveryStatusOf(page)).isEqualTo("AT_RESTAURANT");
    }

    @Test
    void anotherRidersConfirmationIsNotApplied() throws Exception {
        assignment(UUID.randomUUID(), DeliveryStatus.OUT_FOR_DELIVERY);
        JsonNode page = page("READY_FOR_PICKUP", "AT_RESTAURANT");

        progress.apply(page, RIDER);

        assertThat(deliveryStatusOf(page)).isEqualTo("AT_RESTAURANT");
    }

    @Test
    void theAssignmentOnlyRecordsForwardSteps() {
        OrderAssignment a = OrderAssignment.builder().orderId(ORDER).driverId(RIDER).build();
        a.recordDeliveryStatus(DeliveryStatus.OUT_FOR_DELIVERY);
        a.recordDeliveryStatus(DeliveryStatus.AT_RESTAURANT);
        assertThat(a.getDeliveryStatus()).isEqualTo(DeliveryStatus.OUT_FOR_DELIVERY);
        a.recordDeliveryStatus(DeliveryStatus.DELIVERED);
        assertThat(a.getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
    }
}
