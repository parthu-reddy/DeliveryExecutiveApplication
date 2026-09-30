package com.fooddelivery.delivery.controller;

import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.DeliveryExecutiveProfileService;
import com.fooddelivery.delivery.service.OrderAssignmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AdminDeliveryControllerTest {

    @Mock
    private IDeliveryExecutiveRepository repository;

    @Mock
    private DeliveryExecutiveProfileService profileService;

    @Mock
    private OrderAssignmentService orderAssignmentService;

    @InjectMocks
    private AdminDeliveryController controller;

    @Test
    void directForceAssignmentEndpointIsGoneAndCannotCallAssignmentService() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.forceAssignOrder(UUID.randomUUID(), UUID.randomUUID()));

        assertEquals(HttpStatus.GONE, exception.getStatusCode());
        assertEquals("Use the audited CustomerApplication manual intervention endpoint to assign a driver.",
                exception.getReason());
        verifyNoInteractions(orderAssignmentService);
    }

    @Test
    void exposesTheCanonicalConfiguredFleetCitiesForTheAdminMap() {
        ReflectionTestUtils.setField(controller, "allowedFleetCityIds", "BLR, HYD");

        assertEquals(List.of("BLR", "HYD"), controller.getFleetCities().getBody());
    }
}
