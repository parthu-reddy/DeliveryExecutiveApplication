package com.fooddelivery.delivery.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

class DeliveryOnboardingCityControllerTest {
    private DeliveryOnboardingCityController withCities(String configured) {
        var controller = new DeliveryOnboardingCityController();
        ReflectionTestUtils.setField(controller, "allowedFleetCityIds", configured);
        return controller;
    }

    @Test
    void listsTheConfiguredFleetCitiesInOrderWithoutDuplicates() {
        assertThat(withCities("BLR").cities().getData()).isEqualTo(List.of("BLR"));
        assertThat(withCities("BLR, HYD,BLR").cities().getData()).isEqualTo(List.of("BLR", "HYD"));
    }

    @Test
    void refusesAMisconfiguredListInsteadOfOfferingNoCity() {
        assertThatThrownBy(() -> withCities(" ").cities()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requiresASignedInPerson() {
        assertThat(DeliveryOnboardingCityController.class.getAnnotation(PreAuthorize.class).value()).isEqualTo("isAuthenticated()");
    }
}
