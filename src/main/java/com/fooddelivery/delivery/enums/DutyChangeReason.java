package com.fooddelivery.delivery.enums;

/**
 * Why a rider's duty status is what it is, as told to the rider's app in a {@code DUTY_STATUS}
 * socket message. The app shows OFFLINE reasons it did not cause, so the rider knows why the
 * button flipped under them.
 */
public enum DutyChangeReason {
    /** The rider pressed the button. */
    RIDER_REQUEST,
    /** No location reached the server for the liveness window; dispatch cannot place the rider. */
    LOCATION_LOST,
    /** An admin or service suspended the rider. */
    SUSPENDED,
    /** The account was deactivated. */
    ACCOUNT_DEACTIVATED,
    /** Snapshot sent when the rider's socket connects, so a push missed while it was down is corrected. */
    CONNECTED
}
