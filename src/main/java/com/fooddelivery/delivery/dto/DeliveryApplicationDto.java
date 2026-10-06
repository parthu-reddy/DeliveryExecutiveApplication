package com.fooddelivery.delivery.dto;

import com.fooddelivery.common.dto.governmentid.ApplicationDocumentDto;
import com.fooddelivery.common.enums.*;

import java.time.Instant;
import java.util.*;
import com.fooddelivery.common.dto.governmentid.VerificationSummary;

public record DeliveryApplicationDto(
        UUID executiveId,
        ApplicationStatus status,
        Integer version,
        String fullName,
        String phoneNumber,
        String vehicleNumber,
        VehicleClass vehicleType,
        String cityId,
        String photoUrl,
        String rejectionReason,
        Instant submittedAt,
        Instant reviewedAt,
        UUID reviewedBy,
        boolean canEdit,
        boolean documentsAvailable,
        VerificationSummary checks,
        List<ApplicationDocumentDto> documents) {
    @Override
    public String toString() {
        return "DeliveryApplicationDto[executiveId="
                + executiveId
                + ",status="
                + status
                + ",version="
                + version
                + "]";
    }
}
