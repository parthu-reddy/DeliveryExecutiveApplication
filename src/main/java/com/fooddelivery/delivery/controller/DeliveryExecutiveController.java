package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.delivery.service.DeliveryExecutiveProfileService;
import com.fooddelivery.delivery.service.OrderAssignmentService;
import com.fooddelivery.delivery.service.OrderExecutionService;
import com.fooddelivery.common.enums.OrderStatus;
import com.fooddelivery.common.enums.DeliveryStatus;
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

@RestController
@RequestMapping("/api/delivery")
@PreAuthorize("hasRole('DELIVERY')")
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class DeliveryExecutiveController {
private final DeliveryExecutiveProfileService profileService;
    private final OrderAssignmentService orderAssignmentService;
    private final OrderExecutionService orderExecutionService;
    private final com.fooddelivery.delivery.repository.OrderAssignmentRepository assignmentRepository;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final org.springframework.data.redis.listener.RedisMessageListenerContainer redisMessageListenerContainer;


    public static class DeliveryOnboardRequest {
        @NotBlank
        @Size(max = 50)
        private String cityId;
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

public DeliveryOnboardRequest() {
        }

public String getCityId() {
            return this.cityId;
        }

public String getFullName() {
            return this.fullName;
        }

public String getPhoneNumber() {
            return this.phoneNumber;
        }

public String getVehicleNumber() {
            return this.vehicleNumber;
        }

public String getPhotoUrl() {
            return this.photoUrl;
        }

public com.fooddelivery.common.enums.VehicleClass getVehicleType() {
            return this.vehicleType;
        }

public void setCityId(final String cityId) {
            this.cityId = cityId;
        }

public void setFullName(final String fullName) {
            this.fullName = fullName;
        }

public void setPhoneNumber(final String phoneNumber) {
            this.phoneNumber = phoneNumber;
        }

public void setVehicleNumber(final String vehicleNumber) {
            this.vehicleNumber = vehicleNumber;
        }

public void setPhotoUrl(final String photoUrl) {
            this.photoUrl = photoUrl;
        }

public void setVehicleType(final com.fooddelivery.common.enums.VehicleClass vehicleType) {
            this.vehicleType = vehicleType;
        }




    }


    public static class ToggleStatusRequest {
        @NotBlank
        @Size(max = 36)
        @Pattern(regexp = "^[0-9a-fA-F\\-]{36}$")
        private String driverId;
        @NotNull
        private Boolean available;

public ToggleStatusRequest() {
        }

public String getDriverId() {
            return this.driverId;
        }

public Boolean getAvailable() {
            return this.available;
        }

public void setDriverId(final String driverId) {
            this.driverId = driverId;
        }

public void setAvailable(final Boolean available) {
            this.available = available;
        }




    }


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

        public UpdateOrderStatusRequest() {
        }

        public DeliveryStatus getStatus() {
            return this.status;
        }

        public String getPickupOtp() {
            return this.pickupOtp;
        }

        public String getDeliveryOtp() {
            return this.deliveryOtp;
        }

        public Boolean getGoOfflineAfter() {
            return this.goOfflineAfter;
        }

        public void setStatus(final DeliveryStatus status) {
            this.status = status;
        }

        public void setPickupOtp(final String pickupOtp) {
            this.pickupOtp = pickupOtp;
        }

        public void setDeliveryOtp(final String deliveryOtp) {
            this.deliveryOtp = deliveryOtp;
        }

        public void setGoOfflineAfter(final Boolean goOfflineAfter) {
            this.goOfflineAfter = goOfflineAfter;
        }
        
    }

    @PostMapping("/onboard")
    public ResponseEntity<ApiResponse<com.fooddelivery.delivery.entity.DeliveryExecutive>> onboardDriver(java.security.Principal principal, @Valid @RequestBody DeliveryOnboardRequest request) {
        String cityId = request.getCityId();
        String fullName = request.getFullName();
        String phoneNumber = request.getPhoneNumber();
        String vehicleNumber = request.getVehicleNumber();
        String photoUrl = request.getPhotoUrl();
        com.fooddelivery.common.enums.VehicleClass vehicleType = request.getVehicleType();
        com.fooddelivery.delivery.entity.DeliveryExecutive executive = profileService.onboard(UUID.fromString(principal.getName()), fullName, phoneNumber, vehicleNumber, photoUrl, vehicleType, cityId);
        return ResponseEntity.ok(ApiResponse.success(executive, "Delivery Executive onboarded successfully"));
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<com.fooddelivery.delivery.entity.DeliveryExecutive>> getProfile(java.security.Principal principal) {
        UUID driverId = UUID.fromString(principal.getName());
        return profileService.findById(driverId).map(executive -> ResponseEntity.ok(ApiResponse.success(executive, "Profile fetched successfully"))).orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.<com.fooddelivery.delivery.entity.DeliveryExecutive>builder().success(false).message("Profile not found").build()));
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PendingPingResponse {
        private String id;
        private Long expiresAt;
    }

    @GetMapping("/drivers/{driverId}/pings")
    public ResponseEntity<ApiResponse<java.util.List<PendingPingResponse>>> getPendingPings(java.security.Principal principal, @PathVariable UUID driverId) {
        if (!principal.getName().equals(driverId.toString())) {
            return ResponseEntity.status(401).body(ApiResponse.<java.util.List<PendingPingResponse>>builder().success(false).message("Unauthorized").build());
        }
        String pendingOrderId = orderAssignmentService.getPendingPing(driverId);
        if (pendingOrderId == null) {
            return ResponseEntity.ok(ApiResponse.success(java.util.Collections.emptyList(), "No pending pings"));
        }
        Long expiresAt = orderAssignmentService.getPingExpiration(UUID.fromString(pendingOrderId));
        if (expiresAt == null || expiresAt <= System.currentTimeMillis()) {
            return ResponseEntity.ok(ApiResponse.success(java.util.Collections.emptyList(), "No pending pings"));
        }
        return ResponseEntity.ok(ApiResponse.success(java.util.Collections.singletonList(new PendingPingResponse(pendingOrderId, expiresAt)), "Pending ping retrieved"));
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
        return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Status updated successfully").timestamp(LocalDateTime.now()).build());
    }

    @PostMapping("/drivers/{driverId}/orders/{orderId}/accept")
    @PreAuthorize("hasRole('DELIVERY') and #driverId.toString() == authentication.name")
    public ResponseEntity<ApiResponse<Void>> acceptOrder(@PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId) {
        try {
            orderAssignmentService.acceptOrderPing(driverId, orderId);
            return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Order accepted by driver").build());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(400).body(ApiResponse.<Void>builder().success(false).message(ex.getMessage()).build());
        } catch (IllegalStateException ex) {
            int status = 409;
            if (ex.getMessage().contains("expired")) status = 410;
            return ResponseEntity.status(status).body(ApiResponse.<Void>builder().success(false).message(ex.getMessage()).build());
        } catch (java.util.NoSuchElementException ex) {
            return ResponseEntity.status(404).body(ApiResponse.<Void>builder().success(false).message("Driver not found").build());
        } catch (Exception ex) {
            log.error("Unexpected error during acceptOrder", ex);
            return ResponseEntity.status(500).body(ApiResponse.<Void>builder().success(false).message("Internal server error").build());
        }
    }

    @PostMapping("/drivers/{driverId}/orders/{orderId}/reject")
    @PreAuthorize("hasRole('DELIVERY') and #driverId.toString() == authentication.name")
    public ResponseEntity<ApiResponse<Void>> rejectOrder(@PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId) {
        try {
            orderAssignmentService.rejectOrderPing(driverId, orderId);
            return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Order rejected").build());
        } catch (Exception ex) {
            log.error("Failed to reject order", ex);
            return ResponseEntity.status(503).body(ApiResponse.<Void>builder().success(false).message("Decline could not be recorded and will reappear.").build());
        }
    }

    @PostMapping("/drivers/{driverId}/orders/{orderId}/abort")
    @PreAuthorize("hasRole('DELIVERY') and #driverId.toString() == authentication.name")
    public ResponseEntity<ApiResponse<Void>> abortOrder(@PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId) {
        orderExecutionService.abortOrder(driverId, orderId);
        return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Order assignment aborted. Looking for a new driver.").build());
    }

    @PostMapping("/drivers/{driverId}/orders/{orderId}/status")
    @PreAuthorize("hasRole('DELIVERY') and #driverId.toString() == authentication.name")
    public ResponseEntity<ApiResponse<Void>> updateOrderStatus(@PathVariable UUID driverId, @PathVariable UUID orderId, @Valid @RequestBody UpdateOrderStatusRequest request) {
        log.info("DELIVERY_STATUS_UPDATE_REQUESTED driverId={} orderId={} status={} pickupOtpProvided={} deliveryOtpProvided={}",
                driverId, orderId, request.getStatus(), request.getPickupOtp() != null,
                request.getDeliveryOtp() != null);
        orderExecutionService.updateOrderStatus(driverId, orderId, request.getStatus(), request.getPickupOtp(), request.getDeliveryOtp(), request.getGoOfflineAfter());
        return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Order status updated").build());
    }

    @GetMapping(value = "/drivers/{driverId}/orders/{orderId}/restaurant-status-stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('DELIVERY') and #driverId.toString() == authentication.name")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamRestaurantStatus(@PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId) {
        // @PreAuthorize proves the caller is this driver; it says nothing about the order. Without
        // this, any authenticated driver could subscribe to any order's restaurant-status channel.
        // Found by the binding scan added in Phase 2, not by the review that motivated it.
        assignmentRepository.findByOrderId(orderId)
                .filter(a -> a.authorises(driverId))
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException(
                        "This order is not assigned to you."));
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(600000L); // 10 minutes timeout
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
                log.warn("Failed to remove Redis message listener during SSE cleanup", e);
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
// @Getter
