package com.fooddelivery.delivery.service;

/** Shared definition of a usable rider location signal. */
public final class RiderLiveness {
    public static final String LAST_PING_KEY = "driver_last_ping";
    public static final long MAX_SIGNAL_AGE_MS = 60_000L;

    private RiderLiveness() {
    }
}
