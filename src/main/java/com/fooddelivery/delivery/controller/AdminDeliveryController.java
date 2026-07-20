package com.fooddelivery.delivery.controller;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal/admin/delivery")
@RequiredArgsConstructor
public class AdminDeliveryController {

    private final IDeliveryExecutiveRepository repository;
    private final DeliveryService deliveryService;

    @GetMapping("/drivers/available")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<DeliveryExecutive>> getAvailableDrivers() {
        List<DeliveryExecutive> availableDrivers = repository.findByStatus(DeliveryExecutiveStatus.ONLINE);
        return ResponseEntity.ok(availableDrivers);
    }

    @GetMapping("/drivers/available-with-location")
    // @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<com.fooddelivery.delivery.dto.DriverLocationDTO>> getAvailableDriversWithLocation() {
        return ResponseEntity.ok(deliveryService.getAvailableDriversWithLocation());
    }

    @GetMapping("/drivers/all-with-location")
    // @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<com.fooddelivery.delivery.dto.DriverLocationDTO>> getAllDriversWithLocation() {
        return ResponseEntity.ok(deliveryService.getAllDriversWithLocation());
    }


    @PostMapping("/orders/{orderId}/assign")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> forceAssignOrder(
            @PathVariable UUID orderId,
            @RequestParam UUID driverId) {
        deliveryService.forceAssignOrder(orderId, driverId);
        return ResponseEntity.ok().build();
    }
}
