package com.fooddelivery.delivery.controller;

import com.fooddelivery.delivery.service.DeliveryExecutiveProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/v1/internal/delivery")
@lombok.extern.slf4j.Slf4j
@PreAuthorize("hasRole('ADMIN')")
@lombok.RequiredArgsConstructor
public class InternalDeliveryController {
private final DeliveryExecutiveProfileService profileService;

    @PostMapping("/drivers/{driverId}/suspend")
    public ResponseEntity<Void> suspendDriver(@PathVariable UUID driverId) {
        profileService.toggleStatus(driverId, false);
        return ResponseEntity.ok().build();
    }

}
