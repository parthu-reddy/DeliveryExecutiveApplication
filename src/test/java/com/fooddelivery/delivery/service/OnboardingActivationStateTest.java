package com.fooddelivery.delivery.service;

import com.fooddelivery.common.client.GovernmentIdServiceClient;
import com.fooddelivery.common.enums.VehicleClass;
import com.fooddelivery.common.enums.ApplicationStatus;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.fooddelivery.common.dto.governmentid.VerificationSummary;

class OnboardingActivationStateTest {
    private IDeliveryExecutiveRepository repository;
    private DeliveryExecutiveProfileService profiles;
    private OnboardingOrchestratorService service;
    private DeliveryExecutive rider;
    private final VerificationSummary approved =
            new VerificationSummary(true, true, "MCWG", true, true, null);
    @BeforeEach void setUp() {
        repository = mock(IDeliveryExecutiveRepository.class);
        profiles = mock(DeliveryExecutiveProfileService.class);
        service = new OnboardingOrchestratorService(new DeliveryApplicationService(repository,mock(GovernmentIdServiceClient.class),
                mock(com.fooddelivery.common.service.ApplicationEvents.class),mock(com.fooddelivery.common.service.RateLimitingService.class),
                mock(com.fooddelivery.common.audit.AuditReader.class),new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                mock(org.springframework.data.redis.core.StringRedisTemplate.class),mock(com.fooddelivery.delivery.service.duty.RiderDutyNotifier.class)),mock(GovernmentIdServiceClient.class));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        rider = new DeliveryExecutive(); rider.setId(UUID.randomUUID());
        rider.setVehicleType(VehicleClass.MCWG);rider.setVersion(0);rider.setStatus(com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.OFFLINE);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findById(rider.getId())).thenReturn(Optional.of(rider));
    }
    @org.junit.jupiter.api.AfterEach void clearSync() {org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();}
    @Test void approvedInactiveRiderStaysInactiveOnStatusRefresh() {
        rider.setApplicationStatus(ApplicationStatus.APPROVED); rider.setActive(false);
        service.evaluateOnboardingStatus(rider.getId(), approved);
        assertThat(rider.getApplicationStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(rider.isActive()).isFalse();
        rider.setVehicleNumber("KA01AB1234"); rider.setCityId("BLR");
        assertThatThrownBy(() -> RiderReadiness.requireEligibleForDuty(rider, false, java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Account inactive");
    }
    @Test void newlySubmittedChecksPrepareReviewWithoutActivatingTheRider() {
        rider.setApplicationStatus(ApplicationStatus.SUBMITTED); rider.setActive(false);
        service.evaluateOnboardingStatus(rider.getId(), approved);
        assertThat(rider.getApplicationStatus()).isEqualTo(ApplicationStatus.IN_REVIEW);
        assertThat(rider.isActive()).isFalse();
    }
    @Test void approvedActiveRiderStaysActive() {
        rider.setApplicationStatus(ApplicationStatus.APPROVED); rider.setActive(true);
        service.evaluateOnboardingStatus(rider.getId(), approved);
        assertThat(rider.isActive()).isTrue();
    }
    @Test void missingVerificationStillSuspendsAPreviouslyApprovedRider() {
        rider.setApplicationStatus(ApplicationStatus.APPROVED); rider.setActive(true);
        service.evaluateOnboardingStatus(rider.getId(),
                new VerificationSummary(false, false, null, false, false, null));
        assertThat(rider.getApplicationStatus()).isEqualTo(ApplicationStatus.SUSPENDED);
        assertThat(rider.isActive()).isFalse();
    }
}
