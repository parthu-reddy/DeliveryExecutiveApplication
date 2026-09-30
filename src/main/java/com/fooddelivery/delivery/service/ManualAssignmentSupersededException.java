package com.fooddelivery.delivery.service;

/** A command whose Customer-side operation was replaced or completed before Delivery consumed it. */
final class ManualAssignmentSupersededException extends RuntimeException {
    ManualAssignmentSupersededException(String message) {
        super(message);
    }
}
