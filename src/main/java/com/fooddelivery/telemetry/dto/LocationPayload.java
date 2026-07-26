package com.fooddelivery.telemetry.dto;

public record LocationPayload(
    double latitude, 
    double longitude, 
    double speedKmh, 
    boolean isMockLocation, 
    long timestampMs
) {}
