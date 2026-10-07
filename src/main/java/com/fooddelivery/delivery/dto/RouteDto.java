package com.fooddelivery.delivery.dto;

/**
 * The route an order map draws: the maps service's route, flat. {@code durationSeconds} and
 * {@code distanceMeters} are the provider's numeric totals (null when it sent none); {@code distance}
 * and {@code duration} are its readable labels, for display only.
 */
public record RouteDto(String polyline, String distance, String duration, Integer durationSeconds, Integer distanceMeters) {
}
