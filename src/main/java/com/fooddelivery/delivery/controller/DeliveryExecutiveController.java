package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.delivery.service.DeliveryService;
import com.fooddelivery.delivery.service.DeliveryService;
import com.fooddelivery.common.enums.OrderStatus;
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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
        @Size(max = 100)
        private String fullName;
        @NotBlank
        @Size(max = 20)
        @Pattern(regexp = "^\\+?[1-9]\\d{1,14}$")
        private String phoneNumber;
        @NotBlank
        @Size(max = 50)
        private String vehicleNumber;
        @NotBlank
        @Size(max = 255)
        private String photoUrl;
    }

    @Data
    public static class ToggleStatusRequest {
        @NotBlank
        @Size(max = 36)
        @Pattern(regexp = "^[0-9a-fA-F\\-]{36}$")
        private String driverId;
        @NotNull
        private Boolean available;
    }

    @Data
    public static class UpdateOrderStatusRequest {
        @NotNull
        private OrderStatus status;
        
        @Size(max = 10)
        @Pattern(regexp = "^\\d+$")
        private String pickupOtp;

        @Size(max = 10)
        @Pattern(regexp = "^\\d+$")
        private String deliveryOtp;
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
    public ResponseEntity<ApiResponse<Void>> toggleStatus(java.security.Principal principal, @Valid @RequestBody ToggleStatusRequest request) {
        UUID driverId = UUID.fromString(request.getDriverId());
        
        if (!principal.getName().equals(driverId.toString())) {
            return ResponseEntity.status(401).body(ApiResponse.<Void>builder().success(false).message("Unauthorized").build());
        }
        
        boolean available = request.getAvailable();
        
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
    public ResponseEntity<ApiResponse<Object>> updateOrderStatus(
            @PathVariable UUID driverId,
            @PathVariable UUID orderId,
            @Valid @RequestBody UpdateOrderStatusRequest request) {
        deliveryService.updateOrderStatus(driverId, orderId, request.getStatus(), request.getPickupOtp(), request.getDeliveryOtp());
        return ResponseEntity.ok(ApiResponse.<Object>builder().success(true).message("Order status updated").build());
    }

    @PostMapping("/drivers/{driverId}/orders/{orderId}/timeout")
    @PreAuthorize("hasRole('DELIVERY') and #driverId.toString() == authentication.principal")
    public ResponseEntity<ApiResponse<Void>> timeoutDriver(
            @PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId) {
        deliveryService.timeoutDriverPing(driverId, orderId);
        return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Driver ping timed out").build());
    }
}
