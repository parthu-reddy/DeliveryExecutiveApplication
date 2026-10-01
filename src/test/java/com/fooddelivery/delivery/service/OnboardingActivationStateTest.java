package com.fooddelivery.delivery.service;

import com.fooddelivery.common.client.GovernmentIdServiceClient;
import com.fooddelivery.common.enums.VehicleClass;
import com.fooddelivery.common.enums.VerificationStatus;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OnboardingActivationStateTest {
    private IDeliveryExecutiveRepository repository;
    private DeliveryExecutiveProfileService profiles;
    private OnboardingOrchestratorService service;
    private DeliveryExecutive rider;
    private final GovernmentIdServiceClient.VerificationSummary approved =
            new GovernmentIdServiceClient.VerificationSummary(true, true, "MCWG", true, true, null);
    @BeforeEach void setUp() {
        repository = mock(IDeliveryExecutiveRepository.class);
        profiles = mock(DeliveryExecutiveProfileService.class);
        service = new OnboardingOrchestratorService(repository, mock(GovernmentIdServiceClient.class), profiles);
        rider = new DeliveryExecutive(); rider.setId(UUID.randomUUID());
        rider.setVehicleType(VehicleClass.MCWG);
        when(repository.findById(rider.getId())).thenReturn(Optional.of(rider));
    }
    @Test void approvedInactiveRiderStaysInactiveOnStatusRefresh() {
        rider.setVerificationStatus(VerificationStatus.APPROVED); rider.setActive(false);
        service.evaluateOnboardingStatus(rider.getId(), approved);
        assertThat(rider.getVerificationStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(rider.isActive()).isFalse();
        rider.setVehicleNumber("KA01AB1234"); rider.setCityId("BLR");
        assertThatThrownBy(() -> RiderReadiness.requireEligibleForDuty(rider, false, java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Account inactive");
    }
    @Test void newlyApprovedOnboardingStillActivatesTheRider() {
        rider.setVerificationStatus(VerificationStatus.PENDING); rider.setActive(false);
        service.evaluateOnboardingStatus(rider.getId(), approved);
        assertThat(rider.getVerificationStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(rider.isActive()).isTrue();
    }
    @Test void approvedActiveRiderStaysActive() {
        rider.setVerificationStatus(VerificationStatus.APPROVED); rider.setActive(true);
        service.evaluateOnboardingStatus(rider.getId(), approved);
        assertThat(rider.isActive()).isTrue();
    }
    @Test void missingVerificationStillSuspendsAPreviouslyApprovedRider() {
        rider.setVerificationStatus(VerificationStatus.APPROVED); rider.setActive(true);
        service.evaluateOnboardingStatus(rider.getId(),
                new GovernmentIdServiceClient.VerificationSummary(false, false, null, false, false, null));
        assertThat(rider.getVerificationStatus()).isEqualTo(VerificationStatus.PENDING);
        verify(profiles).deactivateDriver(rider.getId());
    }
}
