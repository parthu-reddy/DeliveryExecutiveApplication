package com.fooddelivery.delivery.controller;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.DeliveryExecutiveProfileService;
import com.fooddelivery.delivery.service.OrderAssignmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal/admin/delivery")
@Validated
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class AdminDeliveryController {
private final IDeliveryExecutiveRepository repository;
    private final DeliveryExecutiveProfileService profileService;
    private final OrderAssignmentService orderAssignmentService;

    /** A comma-separated deployment configuration shared with the other fleet map sources. */
    @Value("${platform.fleet.allowed-city-ids:BLR}")
    private String allowedFleetCityIds = "BLR";

    /** Exposes the deployment's canonical fleet scope to the authenticated admin map. */
    @GetMapping("/fleet-cities")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<String>> getFleetCities() {
        return ResponseEntity.ok(com.fooddelivery.common.location.FleetCityScope.parseAllowed(allowedFleetCityIds));
    }

    @GetMapping("/drivers/available")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<org.springframework.data.domain.Page<DeliveryExecutive>> getAvailableDrivers(
            @org.springframework.data.web.PageableDefault(size = 50) org.springframework.data.domain.Pageable pageable) {
        org.springframework.data.domain.Page<DeliveryExecutive> availableDrivers = repository.findByStatus(DeliveryExecutiveStatus.ONLINE, pageable);
        return ResponseEntity.ok(availableDrivers);
    }

    @GetMapping("/drivers/available-with-location")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<com.fooddelivery.delivery.dto.DriverLocationDTO>> getAvailableDriversWithLocation(
            @RequestParam(required = false) @com.fooddelivery.common.location.CityId @Pattern(regexp = com.fooddelivery.common.location.CityIdValidator.REGEX) String cityId,
            @RequestParam(required = false, defaultValue = "0") double lat,
            @RequestParam(required = false, defaultValue = "0") double lng,
            @RequestParam(required = false, defaultValue = "50") double radiusKm) {
        cityId = com.fooddelivery.common.location.FleetCityScope.resolve(cityId, allowedFleetCityIds);
        return ResponseEntity.ok(profileService.getAvailableDriversWithLocation(cityId, lat, lng, radiusKm));
    }

    @GetMapping("/drivers/all-with-location")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<org.springframework.data.domain.Page<com.fooddelivery.delivery.dto.DriverLocationDTO>> getAllDriversWithLocation(
            @RequestParam(required = false) @com.fooddelivery.common.location.CityId @Pattern(regexp = com.fooddelivery.common.location.CityIdValidator.REGEX) String cityId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size) {
        cityId = com.fooddelivery.common.location.FleetCityScope.resolve(cityId, allowedFleetCityIds);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(
                page, size, org.springframework.data.domain.Sort.by("id").ascending());
        return ResponseEntity.ok(profileService.getAllDriversWithLocation(cityId, pageable));
    }

    @PostMapping("/orders/{orderId}/assign")
    @PreAuthorize("hasRole('ADMIN')")
    @io.swagger.v3.oas.annotations.Operation(hidden = true)
    public ResponseEntity<Void> forceAssignOrder(@PathVariable UUID orderId, @RequestParam UUID driverId) {
        // This endpoint used to mutate DeliveryExecutiveApplication directly. It had no
        // CustomerApplication operation record, no immutable actor/reason audit, and could race
        // the customer order state. Manual assignment is now initiated only through the audited
        // CustomerApplication intervention endpoint.
        throw new ResponseStatusException(HttpStatus.GONE,
                "Use the audited CustomerApplication manual intervention endpoint to assign a driver.");
    }

    @GetMapping("/drivers/{driverId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RESTAURANT')")
    public ResponseEntity<DeliveryExecutive> getDriverById(@PathVariable UUID driverId) {
        return repository.findById(driverId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/drivers/batch")
    @PreAuthorize("hasAnyRole('ADMIN', 'RESTAURANT')")
    public ResponseEntity<List<DeliveryExecutive>> getDriversByIds(@RequestBody List<UUID> driverIds) {
        List<DeliveryExecutive> drivers = repository.findAllById(driverIds);
        return ResponseEntity.ok(drivers);
    }

}
