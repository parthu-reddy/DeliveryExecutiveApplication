package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.common.location.FleetCityScope;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cities a rider can apply to deliver in, so the application form offers a choice instead of a
 * free-text city code. Same configuration DeliveryApplicationService resolves against.
 */
@RestController
@PreAuthorize("isAuthenticated()")
public class DeliveryOnboardingCityController {

    @Value("${platform.fleet.allowed-city-ids:BLR}")
    private String allowedFleetCityIds = "BLR";

    @GetMapping("/api/v1/delivery-onboarding/cities")
    public ApiResponse<List<String>> cities() {
        return ApiResponse.success(FleetCityScope.parseAllowed(allowedFleetCityIds), "Fleet cities");
    }
}
