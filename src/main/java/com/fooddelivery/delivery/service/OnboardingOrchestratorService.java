package com.fooddelivery.delivery.service;

import com.fooddelivery.delivery.client.GovernmentIdClient;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.common.enums.VehicleClass;
import com.fooddelivery.common.enums.VerificationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingOrchestratorService {

    private final IDeliveryExecutiveRepository executiveRepository;
    private final GovernmentIdClient governmentIdClient;

    @Transactional
    public void evaluateOnboardingStatus(UUID executiveId) {
        evaluateOnboardingStatus(executiveId, null);
    }

    @Transactional
    public void evaluateOnboardingStatus(UUID executiveId, GovernmentIdClient.VerificationSummary preloadedSummary) {
        DeliveryExecutive executive = executiveRepository.findById(executiveId)
                .orElseThrow(() -> new IllegalArgumentException("Executive not found"));

        try {
            var summary = preloadedSummary != null ? preloadedSummary : governmentIdClient.getVerificationSummary(executiveId);
            
            if (summary.lastBiometricVerificationAt() != null) {
                executive.setLastBiometricVerificationAt(java.time.OffsetDateTime.parse(summary.lastBiometricVerificationAt()));
            }

            if (summary.allDocsApproved() && summary.bankApproved()) {
                VehicleClass vehicleType = executive.getVehicleType();
                
                // If vehicleType is null, skip DL class check (treat as no motor vehicle)
                if (vehicleType != null && vehicleType != VehicleClass.BICYCLE) {
                    if (!isVehicleClassCompatible(vehicleType, summary.dlVehicleClass())) {
                        log.warn("Executive {} vehicle class mismatch! Registered: {}, DL allows: {}", 
                                 executiveId, vehicleType, summary.dlVehicleClass());
                        executive.setVerificationStatus(VerificationStatus.REJECTED);
                        executive.setActive(false);
                        executiveRepository.save(executive);
                        return;
                    }
                }
                
                executive.setVerificationStatus(VerificationStatus.APPROVED);
                executive.setActive(true);
                executiveRepository.save(executive);
                log.info("Executive {} has passed all onboarding checks and is now APPROVED and active.", executiveId);
            } else {
                if (executive.getVerificationStatus() == VerificationStatus.APPROVED) {
                    log.warn("Executive {} no longer has all docs approved. Suspending account.", executiveId);
                    executive.setVerificationStatus(VerificationStatus.PENDING);
                    executive.setActive(false);
                    // Force offline if they are online
                    if (executive.getStatus() == com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.ONLINE) {
                        executive.setStatus(com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.OFFLINE);
                    }
                    executiveRepository.save(executive);
                } else if (summary.lastBiometricVerificationAt() != null) {
                     executiveRepository.save(executive); // Save anyway if biometric updated
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch verification status from GovernmentIDValidationService for executive {}", executiveId, e);
        }
    }

    public boolean isVehicleClassCompatible(VehicleClass registeredType, String dlVehicleClass) {
        if (registeredType == null || dlVehicleClass == null) return false;
        
        // E.g. dlVehicleClass might be "MCWG", "LMV", "MCWOG"
        String dlClass = dlVehicleClass.toUpperCase();
        
        switch (registeredType) {
            case BICYCLE:
                return true; // No DL needed for bicycle, but if they have one, it's fine
            case EV_TWO_WHEELER:
            case MCWG:
                return dlClass.contains("MCWG") || dlClass.contains("LMV") || dlClass.contains("MCWOG");
            case LMV:
                return dlClass.contains("LMV");
            default:
                return false;
        }
    }
}


