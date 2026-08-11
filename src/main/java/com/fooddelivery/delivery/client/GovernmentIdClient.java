package com.fooddelivery.delivery.client;

import com.fooddelivery.common.enums.VerificationStatus;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(name = "government-id-validation-service", fallback = GovernmentIdClientFallback.class)
public interface GovernmentIdClient {

    @GetMapping("/api/v1/verification/status/{executiveId}")
    VerificationSummary getVerificationSummary(@PathVariable("executiveId") UUID executiveId);

    @GetMapping("/api/v1/verification/upload-url")
    java.util.Map<String, String> getPresignedUploadUrl(
            @org.springframework.web.bind.annotation.RequestParam("docType") String docType,
            @org.springframework.web.bind.annotation.RequestParam("contentType") String contentType);

    @GetMapping("/api/v1/verification/download-url")
    java.util.Map<String, String> getPresignedDownloadUrl(
            @org.springframework.web.bind.annotation.RequestParam("objectKey") String objectKey);

    @org.springframework.web.bind.annotation.PostMapping("/api/v1/verification/driving-license")
    Object verifyDrivingLicense(@org.springframework.web.bind.annotation.RequestBody Object request);

    @org.springframework.web.bind.annotation.PostMapping("/api/v1/verification/vehicle-rc")
    Object verifyVehicleRC(@org.springframework.web.bind.annotation.RequestBody Object request);

    @org.springframework.web.bind.annotation.PostMapping("/api/v1/verification/bank-account")
    Object verifyBankAccount(@org.springframework.web.bind.annotation.RequestBody Object request);

    @org.springframework.web.bind.annotation.PostMapping("/api/v1/verification/biometric")
    Object verifyBiometric(@org.springframework.web.bind.annotation.RequestBody Object request);

    record VerificationSummary(boolean allDocsApproved, boolean bankApproved, String dlVehicleClass, boolean dlApproved, boolean rcApproved, String lastBiometricVerificationAt) {}
}
