package com.fooddelivery.delivery.service;

import com.fooddelivery.common.client.GovernmentIdServiceClient;
import com.fooddelivery.common.enums.VehicleClass;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.UUID;
import com.fooddelivery.common.dto.governmentid.VerificationSummary;

/** Compatibility checks delegate every lifecycle decision to the application owner. */
@Service
@RequiredArgsConstructor
public class OnboardingOrchestratorService {
    private final DeliveryApplicationService applications;
    private final GovernmentIdServiceClient government;

    public void evaluateOnboardingStatus(UUID id) {
        applications.observeChecks(id, government.getVerificationSummary(id));
    }

    public void evaluateOnboardingStatus(
            UUID id, VerificationSummary summary) {
        applications.observeChecks(id, summary);
    }

    public static boolean isVehicleClassCompatible(
            VehicleClass registeredType, String dlVehicleClass) {
        if (registeredType == null || dlVehicleClass == null) return false;
        // E.g. dlVehicleClass might be "MCWG", "LMV", "MCWOG"
        String dlClass = dlVehicleClass.toUpperCase();
        switch (registeredType) {
            case BICYCLE:
                return true; // No DL needed for bicycle, but if they have one, it's fine
            case EV_TWO_WHEELER:
            case MCWG:
                return dlClass.contains("MCWG")
                        || dlClass.contains("LMV")
                        || dlClass.contains("MCWOG");
            case LMV:
                return dlClass.contains("LMV");
            default:
                return false;
        }
    }
}
