package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.delivery.client.GovernmentIdClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/delivery/verification")
@RequiredArgsConstructor
@PreAuthorize("hasRole('DELIVERY')")
public class DeliveryVerificationController {

    private final GovernmentIdClient governmentIdClient;
    private final com.fooddelivery.delivery.service.OnboardingOrchestratorService onboardingOrchestratorService;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Object>> getVerificationStatus(Principal principal) {
        UUID executiveId = UUID.fromString(principal.getName());
        GovernmentIdClient.VerificationSummary summary = governmentIdClient.getVerificationSummary(executiveId);
        
        // Evaluate and sync onboarding status with the Executive Profile
        onboardingOrchestratorService.evaluateOnboardingStatus(executiveId, summary);
        
        return ResponseEntity.ok(ApiResponse.success(summary, "Verification status fetched"));
    }

    @GetMapping("/upload-url")
    public ResponseEntity<ApiResponse<Map<String, String>>> getPresignedUploadUrl(
            @RequestParam("docType") String docType,
            @RequestParam("contentType") String contentType) {
        
        Map<String, String> response = governmentIdClient.getPresignedUploadUrl(docType, contentType);
        return ResponseEntity.ok(ApiResponse.success(response, "Upload URL generated"));
    }

    @GetMapping("/download-url")
    public ResponseEntity<ApiResponse<Map<String, String>>> getPresignedDownloadUrl(
            @RequestParam("objectKey") String objectKey) {
        
        Map<String, String> response = governmentIdClient.getPresignedDownloadUrl(objectKey);
        return ResponseEntity.ok(ApiResponse.success(response, "Download URL generated"));
    }

    @PostMapping("/driving-license")
    public ResponseEntity<ApiResponse<Object>> verifyDrivingLicense(
            @RequestBody Map<String, Object> request) {
        
        Object result = governmentIdClient.verifyDrivingLicense(request);
        return ResponseEntity.ok(ApiResponse.success(result, "Driving license verification initiated"));
    }

    @PostMapping("/vehicle-rc")
    public ResponseEntity<ApiResponse<Object>> verifyVehicleRC(
            @RequestBody Map<String, Object> request) {
        
        Object result = governmentIdClient.verifyVehicleRC(request);
        return ResponseEntity.ok(ApiResponse.success(result, "Vehicle RC verification initiated"));
    }

    @PostMapping("/bank-account")
    public ResponseEntity<ApiResponse<Object>> verifyBankAccount(
            @RequestBody Map<String, Object> request) {
        
        Object result = governmentIdClient.verifyBankAccount(request);
        return ResponseEntity.ok(ApiResponse.success(result, "Bank account verification initiated"));
    }

    @PostMapping("/biometric")
    public ResponseEntity<ApiResponse<Object>> verifyBiometric(
            @RequestBody Map<String, Object> request) {
        
        Object result = governmentIdClient.verifyBiometric(request);
        return ResponseEntity.ok(ApiResponse.success(result, "Biometric verification initiated"));
    }
}
