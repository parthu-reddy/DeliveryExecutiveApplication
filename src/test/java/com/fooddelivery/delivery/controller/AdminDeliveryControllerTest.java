package com.fooddelivery.delivery.controller;

import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.DeliveryExecutiveProfileService;
import com.fooddelivery.delivery.service.OrderAssignmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void exposesTheCanonicalConfiguredFleetCitiesForTheAdminMap() {
        ReflectionTestUtils.setField(controller, "allowedFleetCityIds", "BLR, HYD");

        assertEquals(List.of("BLR", "HYD"), controller.getFleetCities().getBody());
    }
}
