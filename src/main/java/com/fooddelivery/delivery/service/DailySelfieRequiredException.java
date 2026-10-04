package com.fooddelivery.delivery.service;

/** A recoverable duty precondition, distinct from application approval or activation. */
public class DailySelfieRequiredException extends IllegalArgumentException {
    public DailySelfieRequiredException() {
        super("Biometric verification required: Please complete your daily selfie verification to go online.");
    }
}
