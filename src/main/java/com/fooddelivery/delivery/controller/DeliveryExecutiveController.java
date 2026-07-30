package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.delivery.service.DeliveryExecutiveProfileService;
import com.fooddelivery.delivery.service.OrderAssignmentService;
import com.fooddelivery.delivery.service.OrderExecutionService;
import com.fooddelivery.common.enums.OrderStatus;
import com.fooddelivery.common.enums.DeliveryStatus;
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

    private final DeliveryExecutiveProfileService profileService;
    private final OrderAssignmentService orderAssignmentService;
    private final OrderExecutionService orderExecutionService;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final org.springframework.data.redis.listener.RedisMessageListenerContainer redisMessageListenerContainer;

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
        @Size(max = 255)
        private String photoUrl;
        private com.fooddelivery.common.enums.VehicleClass vehicleType;
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
        private DeliveryStatus status;
        
        @Size(max = 10)
        @Pattern(regexp = "^\\d+$")
        private String pickupOtp;

        @Size(max = 10)
        @Pattern(regexp = "^\\d+$")
        private String deliveryOtp;

        private Boolean goOfflineAfter;
    }

    @PostMapping("/onboard")
    public ResponseEntity<ApiResponse<com.fooddelivery.delivery.entity.DeliveryExecutive>> onboardDriver(
            java.security.Principal principal, 
            @Valid @RequestBody DeliveryOnboardRequest request) {
        String fullName = request.getFullName();
        String phoneNumber = request.getPhoneNumber();
        String vehicleNumber = request.getVehicleNumber();
        String photoUrl = request.getPhotoUrl();
        com.fooddelivery.common.enums.VehicleClass vehicleType = request.getVehicleType();
        com.fooddelivery.delivery.entity.DeliveryExecutive executive = profileService.onboard(UUID.fromString(principal.getName()), fullName, phoneNumber, vehicleNumber, photoUrl, vehicleType);
        return ResponseEntity.ok(ApiResponse.success(executive, "Delivery Executive onboarded successfully"));
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<com.fooddelivery.delivery.entity.DeliveryExecutive>> getProfile(
            @RequestParam("phoneNumber") String phoneNumber) {
        return profileService.findByPhoneNumber(phoneNumber)
                .map(executive -> ResponseEntity.ok(ApiResponse.success(executive, "Profile fetched successfully")))
                .orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.<com.fooddelivery.delivery.entity.DeliveryExecutive>builder().success(false).message("Profile not found").build()));
    }


    @GetMapping("/drivers/{driverId}/pings")
    public ResponseEntity<ApiResponse<java.util.List<Map<String, Object>>>> getPendingPings(java.security.Principal principal, @PathVariable UUID driverId) {
        if (!principal.getName().equals(driverId.toString())) {
            return ResponseEntity.status(401).body(ApiResponse.<java.util.List<Map<String, Object>>>builder().success(false).message("Unauthorized").build());
        }
        
        String pendingOrderId = orderAssignmentService.getPendingPing(driverId);
        if (pendingOrderId == null) {
            return ResponseEntity.ok(ApiResponse.success(java.util.Collections.emptyList(), "No pending pings"));
        }
        
        Long expiresAt = orderAssignmentService.getPingExpiration(UUID.fromString(pendingOrderId));
        if (expiresAt == null) {
            return ResponseEntity.ok(ApiResponse.success(java.util.Collections.emptyList(), "No pending pings"));
        }
        
        return ResponseEntity.ok(ApiResponse.success(
            java.util.Collections.singletonList(
                Map.of("id", pendingOrderId, "expiresAt", expiresAt)
            ), 
            "Pending ping retrieved"
        ));
    }

    @PostMapping("/status")
    public ResponseEntity<ApiResponse<Void>> toggleStatus(java.security.Principal principal, @Valid @RequestBody ToggleStatusRequest request) {
        UUID driverId = UUID.fromString(request.getDriverId());
        
        if (!principal.getName().equals(driverId.toString())) {
            return ResponseEntity.status(401).body(ApiResponse.<Void>builder().success(false).message("Unauthorized").build());
        }
        
        boolean available = request.getAvailable();
        
        try {
            profileService.toggleStatus(driverId, available);
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
        try {
            orderAssignmentService.acceptOrderPing(driverId, orderId);
            return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Order accepted by driver").build());
        } catch (IllegalStateException ex) {
            return ResponseEntity.badRequest().body(ApiResponse.<Void>builder().success(false).message(ex.getMessage()).build());
        }
    }

    @PostMapping("/drivers/{driverId}/orders/{orderId}/reject")
    @PreAuthorize("hasRole('DELIVERY') and #driverId.toString() == authentication.principal")
    public ResponseEntity<ApiResponse<Void>> rejectOrder(
            @PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId) {
        orderAssignmentService.rejectOrderPing(driverId, orderId);
        return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Order rejected").build());
    }

    @PostMapping("/drivers/{driverId}/orders/{orderId}/abort")
    @PreAuthorize("hasRole('DELIVERY') and #driverId.toString() == authentication.principal")
    public ResponseEntity<ApiResponse<Void>> abortOrder(
            @PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId) {
        orderExecutionService.abortOrder(driverId, orderId);
        return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Order assignment aborted. Looking for a new driver.").build());
    }

    @PostMapping("/drivers/{driverId}/orders/{orderId}/status")
    @PreAuthorize("hasRole('DELIVERY') and #driverId.toString() == authentication.principal")
    public ResponseEntity<ApiResponse<Object>> updateOrderStatus(
            @PathVariable UUID driverId,
            @PathVariable UUID orderId,
            @Valid @RequestBody UpdateOrderStatusRequest request) {
        orderExecutionService.updateOrderStatus(driverId, orderId, request.getStatus(), request.getPickupOtp(), request.getDeliveryOtp(), request.getGoOfflineAfter());
        return ResponseEntity.ok(ApiResponse.<Object>builder().success(true).message("Order status updated").build());
    }

    @GetMapping(value = "/drivers/{driverId}/orders/{orderId}/restaurant-status-stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('DELIVERY') and #driverId.toString() == authentication.principal")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamRestaurantStatus(
            @PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId) {

        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = 
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(600000L); // 10 minutes timeout
        
        String trackingChannel = "restaurant-status:order:" + orderId;
        
        org.springframework.data.redis.connection.MessageListener listener = (message, pattern) -> {
            try {
                String status = new String(message.getBody());
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("status-update").data(status));
            } catch (java.io.IOException e) {
                emitter.completeWithError(e);
            }
        };
        
        org.springframework.data.redis.listener.ChannelTopic topic = new org.springframework.data.redis.listener.ChannelTopic(trackingChannel);
        redisMessageListenerContainer.addMessageListener(listener, topic);
        
        // Cleanup: remove the listener when the SSE ends
        Runnable cleanup = () -> {
            try {
                redisMessageListenerContainer.removeMessageListener(listener, topic);
            } catch (Exception e) {
            }
        };
        
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(ex -> {
            cleanup.run();
        });
        
        // Send initial connection event
        try {
            emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("connected").data("Status tracking started for order: " + orderId));
        } catch (java.io.IOException e) {
            cleanup.run();
            emitter.completeWithError(e);
        }
        
        return emitter;
    }
}
