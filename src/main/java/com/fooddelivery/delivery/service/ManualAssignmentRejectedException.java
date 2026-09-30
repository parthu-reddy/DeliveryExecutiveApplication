package com.fooddelivery.delivery.service;

/**
 * A deterministic business rejection for an audited manual-assignment operation.
 *
 * <p>These rejections are safe to complete with a durable failure result. Infrastructure and
 * consistency failures must continue to escape to Kafka retry handling.
 */
public final class ManualAssignmentRejectedException extends RuntimeException {

    private final String reasonCode;

    public ManualAssignmentRejectedException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public ManualAssignmentRejectedException(String reasonCode, String message, Throwable cause) {
        super(message, cause);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
