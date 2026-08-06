package com.fooddelivery.delivery.controller;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.DeliveryExecutiveProfileService;
import com.fooddelivery.delivery.service.OrderAssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/internal/admin/delivery")
@RequiredArgsConstructor
@Slf4j
public class AdminDeliveryController {

    private final IDeliveryExecutiveRepository repository;
    private final DeliveryExecutiveProfileService profileService;
    private final OrderAssignmentService orderAssignmentService;

    @GetMapping("/drivers/available")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<DeliveryExecutive>> getAvailableDrivers() {
        List<DeliveryExecutive> availableDrivers = repository.findByStatus(DeliveryExecutiveStatus.ONLINE);
        return ResponseEntity.ok(availableDrivers);
    }

    @GetMapping("/drivers/available-with-location")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<com.fooddelivery.delivery.dto.DriverLocationDTO>> getAvailableDriversWithLocation(
            @RequestParam(required = false, defaultValue = "0") double lat,
            @RequestParam(required = false, defaultValue = "0") double lng,
            @RequestParam(required = false, defaultValue = "50") double radiusKm) {
        return ResponseEntity.ok(profileService.getAvailableDriversWithLocation(lat, lng, radiusKm));
    }

    @GetMapping("/drivers/all-with-location")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<com.fooddelivery.delivery.dto.DriverLocationDTO>> getAllDriversWithLocation() {
        return ResponseEntity.ok(profileService.getAllDriversWithLocation());
    }


    @PostMapping("/orders/{orderId}/assign")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> forceAssignOrder(
            @PathVariable UUID orderId,
            @RequestParam UUID driverId) {
        orderAssignmentService.forceAssignOrder(orderId, driverId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/drivers/{driverId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RESTAURANT')")
    public ResponseEntity<DeliveryExecutive> getDriverById(@PathVariable UUID driverId) {
        return repository.findById(driverId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/drivers/batch")
    @PreAuthorize("hasAnyRole('ADMIN', 'RESTAURANT')")
    public ResponseEntity<List<DeliveryExecutive>> getDriversByIds(@RequestBody List<UUID> driverIds) {
        List<DeliveryExecutive> drivers = repository.findAllById(driverIds);
        return ResponseEntity.ok(drivers);
    }
}
