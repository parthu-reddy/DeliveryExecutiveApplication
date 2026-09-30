package com.fooddelivery.delivery.service;

import com.fooddelivery.common.enums.VerificationStatus;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;

import java.time.Duration;
import java.time.Instant;

/**
 * The authoritative preconditions for putting a rider on duty or assigning a manual delivery.
 *
 * <p>Keeping these checks together prevents an administrator override from quietly bypassing
 * onboarding, activation, or daily biometric requirements that the rider-facing duty action
 * already enforces.
 */
public final class RiderReadiness {

    private RiderReadiness() {
    }

    public static void requireEligibleForDuty(
            DeliveryExecutive executive,
            boolean biometricVerificationEnabled,
            Instant now) {
        if (executive.getVehicleNumber() == null || executive.getVehicleNumber().trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Registration incomplete: Please complete registration before going online.");
        }
        if (!com.fooddelivery.common.location.CityIdValidator.isCanonical(executive.getCityId())) {
            throw new IllegalArgumentException("Registration incomplete: No operating city on your profile.");
        }
        if (executive.getVerificationStatus() != VerificationStatus.APPROVED) {
            throw new IllegalArgumentException("Onboarding incomplete: Driver verification status is "
                    + executive.getVerificationStatus() + ". Must be APPROVED to go online.");
        }
        if (!executive.isActive()) {
            throw new IllegalArgumentException(
                    "Account inactive: Driver account is currently deactivated or suspended.");
        }
        if (biometricVerificationEnabled
                && (executive.getLastBiometricVerificationAt() == null
                || executive.getLastBiometricVerificationAt().isBefore(now.minus(Duration.ofHours(24))))) {
            throw new IllegalArgumentException(
                    "Biometric verification required: Please complete your daily selfie verification to go online.");
        }
    }

    public static void requireManualAssignmentReady(
            DeliveryExecutive executive,
            String dispatchCityId,
            boolean biometricVerificationEnabled,
            Instant now,
            boolean hasFreshLocation) {
        try {
            requireEligibleForDuty(executive, biometricVerificationEnabled, now);
        } catch (IllegalArgumentException ineligible) {
            throw new ManualAssignmentRejectedException(
                    "DRIVER_NOT_ELIGIBLE", "Driver is not eligible for manual assignment.", ineligible);
        }
        if (executive.getStatus() != DeliveryExecutiveStatus.ONLINE) {
            throw new ManualAssignmentRejectedException(
                    "DRIVER_NOT_ONLINE", "Driver must be ONLINE for manual assignment.");
        }
        if (dispatchCityId == null || !dispatchCityId.equals(executive.getCityId())) {
            throw new ManualAssignmentRejectedException(
                    "DRIVER_OUTSIDE_DISPATCH_CITY",
                    "Driver is not registered in the order's dispatch city.");
        }
        if (!hasFreshLocation) {
            throw new ManualAssignmentRejectedException(
                    "DRIVER_LOCATION_STALE",
                    "Driver has no current location signal and cannot be manually assigned.");
        }
    }
}
