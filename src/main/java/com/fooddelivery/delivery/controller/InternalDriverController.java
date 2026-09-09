package com.fooddelivery.delivery.controller;

import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/internal/drivers")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SERVICE', 'ADMIN')")
public class InternalDriverController {

    private final IDeliveryExecutiveRepository profileRepository;

    @GetMapping("/{driverId}")
    public ResponseEntity<Map<String, String>> getDriverSummary(@PathVariable UUID driverId) {
        return profileRepository.findById(driverId)
                .map(profile -> ResponseEntity.ok(Map.of(
                        "id", profile.getId().toString(),
                        "fullName", profile.getFullName()
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/summaries")
    public ResponseEntity<List<Map<String, String>>> getDriverSummaries(@RequestBody List<UUID> driverIds) {
        List<Map<String, String>> summaries = profileRepository.findAllById(driverIds).stream()
                .map(profile -> Map.of(
                        "id", profile.getId().toString(),
                        "fullName", profile.getFullName()
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(summaries);
    }
}
