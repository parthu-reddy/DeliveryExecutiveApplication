package com.fooddelivery.delivery.controller;

import com.fooddelivery.delivery.service.DeliveryExecutiveProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal/delivery")
public class InternalDeliveryController {
    @java.lang.SuppressWarnings("all")
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InternalDeliveryController.class);
    private final DeliveryExecutiveProfileService profileService;

    @PostMapping("/drivers/{driverId}/suspend")
    public ResponseEntity<Void> suspendDriver(@PathVariable UUID driverId) {
        profileService.toggleStatus(driverId, false);
        return ResponseEntity.ok().build();
    }

    @java.lang.SuppressWarnings("all")
    public InternalDeliveryController(final DeliveryExecutiveProfileService profileService) {
        this.profileService = profileService;
    }
}
