package com.fooddelivery.delivery.client;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;

@Component("deliveryexecutiveGovernmentIdClientFallback")
public class GovernmentIdClientFallback implements GovernmentIdClient {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GovernmentIdClientFallback.class);

    @Override
    public VerificationSummary getVerificationSummary(UUID executiveId) {
        log.error("GovernmentId service is down. Fallback triggered for getVerificationSummary");
        throw new RuntimeException("GovernmentId service is currently unavailable");
    }

    @Override
    public Map<String, String> getPresignedUploadUrl(String docType, String contentType) {
        log.error("GovernmentId service is down. Fallback triggered for getPresignedUploadUrl");
        throw new RuntimeException("GovernmentId service is currently unavailable");
    }

    @Override
    public Map<String, String> getPresignedDownloadUrl(String objectKey) {
        log.error("GovernmentId service is down. Fallback triggered for getPresignedDownloadUrl");
        throw new RuntimeException("GovernmentId service is currently unavailable");
    }

    @Override
    public Object verifyDrivingLicense(Object request) {
        log.error("GovernmentId service is down. Fallback triggered for verifyDrivingLicense");
        throw new RuntimeException("GovernmentId service is currently unavailable");
    }

    @Override
    public Object verifyVehicleRC(Object request) {
        log.error("GovernmentId service is down. Fallback triggered for verifyVehicleRC");
        throw new RuntimeException("GovernmentId service is currently unavailable");
    }

    @Override
    public Object verifyBankAccount(Object request) {
        log.error("GovernmentId service is down. Fallback triggered for verifyBankAccount");
        throw new RuntimeException("GovernmentId service is currently unavailable");
    }

    @Override
    public Object verifyBiometric(Object request) {
        log.error("GovernmentId service is down. Fallback triggered for verifyBiometric");
        throw new RuntimeException("GovernmentId service is currently unavailable");
    }
}
