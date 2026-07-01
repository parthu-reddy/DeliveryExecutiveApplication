package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.delivery.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/delivery")
@RequiredArgsConstructor
public class DeliveryExecutiveController {

    private final DeliveryService deliveryService;

    @PostMapping("/onboard")
    public ResponseEntity<ApiResponse<com.fooddelivery.delivery.entity.DeliveryExecutive>> onboardDriver(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        String phoneNumber = request.get("phoneNumber");
        String vehicleNumber = request.get("vehicleNumber");
        com.fooddelivery.delivery.entity.DeliveryExecutive executive = deliveryService.onboard(name, phoneNumber, vehicleNumber);
        return ResponseEntity.ok(ApiResponse.success(executive, "Delivery Executive onboarded successfully"));
    }

    @PostMapping("/status")
    public ResponseEntity<ApiResponse<Void>> toggleStatus(@RequestBody Map<String, Object> request) {
        UUID driverId = UUID.fromString((String) request.get("driverId"));
        boolean available = (Boolean) request.get("available");
        
        deliveryService.toggleStatus(driverId, available);
        
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .success(true)
                .message("Status updated successfully")
                .timestamp(LocalDateTime.now())
                .build());
    }

    @PostMapping("/drivers/{driverId}/orders/{orderId}/accept")
    public ResponseEntity<ApiResponse<Void>> acceptOrder(
            @PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId) {
        deliveryService.acceptOrderPing(driverId, orderId);
        return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Order accepted by driver").build());
    }

    @PostMapping("/drivers/{driverId}/orders/{orderId}/reject")
    public ResponseEntity<ApiResponse<Void>> rejectOrder(
            @PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId) {
        deliveryService.rejectOrderPing(driverId, orderId);
        return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Order rejected").build());
    }

    @PostMapping("/drivers/{driverId}/orders/{orderId}/status")
    public ResponseEntity<ApiResponse<Void>> updateOrderStatus(
            @PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId, @RequestBody Map<String, String> request) {
        String status = request.get("status");
        deliveryService.updateOrderStatus(driverId, orderId, status);
        return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Order status updated").build());
    }

    @PostMapping("/drivers/{driverId}/orders/{orderId}/timeout")
    public ResponseEntity<ApiResponse<Void>> timeoutDriver(
            @PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId) {
        deliveryService.timeoutDriverPing(driverId, orderId);
        return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Driver ping timed out").build());
    }
}
