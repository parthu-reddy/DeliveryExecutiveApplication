package com.fooddelivery.delivery.service;

/** The durable result of consuming one manual-assignment command. */
public record ManualForceAssignmentResult(
        boolean applied,
        boolean replayed,
        boolean ignoredAsSuperseded,
        String failureCode) {

    public static ManualForceAssignmentResult appliedResult() {
        return new ManualForceAssignmentResult(true, false, false, null);
    }

    public static ManualForceAssignmentResult replayResult() {
        return new ManualForceAssignmentResult(false, true, false, null);
    }

    public static ManualForceAssignmentResult rejected(String failureCode) {
        return new ManualForceAssignmentResult(false, false, false, failureCode);
    }

    public static ManualForceAssignmentResult superseded() {
        return new ManualForceAssignmentResult(false, false, true, null);
    }

    public boolean rejected() {
        return failureCode != null;
    }
}
