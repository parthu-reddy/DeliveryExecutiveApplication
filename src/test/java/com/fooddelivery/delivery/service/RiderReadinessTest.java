package com.fooddelivery.delivery.service;
import com.fooddelivery.common.enums.ApplicationStatus;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
class RiderReadinessTest {
    DeliveryExecutive rider() {var rider=new DeliveryExecutive();rider.setVehicleNumber("KA01AB1234");rider.setCityId("BLR");rider.setActive(true);return rider;}
    @ParameterizedTest @EnumSource(value=ApplicationStatus.class,names="APPROVED",mode=EnumSource.Mode.EXCLUDE)
    void allUnapprovedApplicationsAreRefusedEvenIfActive(ApplicationStatus state) {
        var rider=rider();rider.setApplicationStatus(state);
        assertThatThrownBy(() -> RiderReadiness.requireEligibleForDuty(rider,false,Instant.now())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Admin approval");
    }
    @Test void approvedActiveRiderStillNeedsVehicleCityAndRequiredDailyBiometric() {
        var rider=rider();rider.setApplicationStatus(ApplicationStatus.APPROVED);
        assertThatCode(() -> RiderReadiness.requireEligibleForDuty(rider,false,Instant.now())).doesNotThrowAnyException();
        assertThatThrownBy(() -> RiderReadiness.requireEligibleForDuty(rider,true,Instant.now())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("daily selfie");
        rider.setLastBiometricVerificationAt(Instant.now());assertThatCode(() -> RiderReadiness.requireEligibleForDuty(rider,true,Instant.now())).doesNotThrowAnyException();
        rider.setActive(false);assertThatThrownBy(() -> RiderReadiness.requireEligibleForDuty(rider,false,Instant.now())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inactive");
    }
}
