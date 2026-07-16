package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.delivery.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@RestController
@RequestMapping("/api/delivery")
@RequiredArgsConstructor
@PreAuthorize("hasRole('DELIVERY')")
public class DeliveryExecutiveController {

    private final DeliveryService deliveryService;

    @Data
    public static class DeliveryOnboardRequest {
        @NotBlank
        private String fullName;
        @NotBlank
        private String phoneNumber;
        @NotBlank
        private String vehicleNumber;
        @NotBlank
        private String photoUrl;
    }

    @PostMapping("/onboard")
    public ResponseEntity<ApiResponse<com.fooddelivery.delivery.entity.DeliveryExecutive>> onboardDriver(
            java.security.Principal principal, 
            @Valid @RequestBody DeliveryOnboardRequest request) {
        String fullName = request.getFullName();
        String phoneNumber = request.getPhoneNumber();
        String vehicleNumber = request.getVehicleNumber();
        String photoUrl = request.getPhotoUrl();
        com.fooddelivery.delivery.entity.DeliveryExecutive executive = deliveryService.onboard(UUID.fromString(principal.getName()), fullName, phoneNumber, vehicleNumber, photoUrl);
        return ResponseEntity.ok(ApiResponse.success(executive, "Delivery Executive onboarded successfully"));
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<com.fooddelivery.delivery.entity.DeliveryExecutive>> getProfile(
            @RequestParam("phoneNumber") String phoneNumber) {
        return deliveryService.findByPhoneNumber(phoneNumber)
                .map(executive -> ResponseEntity.ok(ApiResponse.success(executive, "Profile fetched successfully")))
                .orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.<com.fooddelivery.delivery.entity.DeliveryExecutive>builder().success(false).message("Profile not found").build()));
    }

    @PostMapping("/status")
    public ResponseEntity<ApiResponse<Void>> toggleStatus(java.security.Principal principal, @RequestBody Map<String, Object> request) {
        UUID driverId = UUID.fromString((String) request.get("driverId"));
        
        if (!principal.getName().equals(driverId.toString())) {
            return ResponseEntity.status(401).body(ApiResponse.<Void>builder().success(false).message("Unauthorized").build());
        }
        
        boolean available = (Boolean) request.get("available");
        
        try {
            deliveryService.toggleStatus(driverId, available);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<Void>builder().success(false).message(e.getMessage()).build());
        }
        
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .success(true)
                .message("Status updated successfully")
                .timestamp(LocalDateTime.now())
                .build());
    }

    @PostMapping("/drivers/{driverId}/orders/{orderId}/accept")
    @PreAuthorize("hasRole('DELIVERY') and #driverId.toString() == authentication.principal")
    public ResponseEntity<ApiResponse<Void>> acceptOrder(
            @PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId) {
        deliveryService.acceptOrderPing(driverId, orderId);
        return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Order accepted by driver").build());
    }

    @PostMapping("/drivers/{driverId}/orders/{orderId}/reject")
    @PreAuthorize("hasRole('DELIVERY') and #driverId.toString() == authentication.principal")
    public ResponseEntity<ApiResponse<Void>> rejectOrder(
            @PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId) {
        deliveryService.rejectOrderPing(driverId, orderId);
        return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Order rejected").build());
    }

    @PostMapping("/drivers/{driverId}/orders/{orderId}/status")
    @PreAuthorize("hasRole('DELIVERY') and #driverId.toString() == authentication.principal")
    public ResponseEntity<ApiResponse<Void>> updateOrderStatus(
            @PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId, @RequestBody Map<String, String> request) {
        String status = request.get("status");
        deliveryService.updateOrderStatus(driverId, orderId, status);
        return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Order status updated").build());
    }

    @PostMapping("/drivers/{driverId}/orders/{orderId}/timeout")
    @PreAuthorize("hasRole('DELIVERY') and #driverId.toString() == authentication.principal")
    public ResponseEntity<ApiResponse<Void>> timeoutDriver(
            @PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId) {
        deliveryService.timeoutDriverPing(driverId, orderId);
        return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Driver ping timed out").build());
    }
}
