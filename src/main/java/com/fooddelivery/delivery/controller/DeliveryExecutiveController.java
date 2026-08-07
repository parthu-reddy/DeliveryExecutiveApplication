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
@PreAuthorize("hasRole(\'DELIVERY\')")
public class DeliveryExecutiveController {
    @java.lang.SuppressWarnings("all")
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DeliveryExecutiveController.class);
    private final DeliveryExecutiveProfileService profileService;
    private final OrderAssignmentService orderAssignmentService;
    private final OrderExecutionService orderExecutionService;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final org.springframework.data.redis.listener.RedisMessageListenerContainer redisMessageListenerContainer;


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

        @java.lang.SuppressWarnings("all")
        public DeliveryOnboardRequest() {
        }

        @java.lang.SuppressWarnings("all")
        public String getFullName() {
            return this.fullName;
        }

        @java.lang.SuppressWarnings("all")
        public String getPhoneNumber() {
            return this.phoneNumber;
        }

        @java.lang.SuppressWarnings("all")
        public String getVehicleNumber() {
            return this.vehicleNumber;
        }

        @java.lang.SuppressWarnings("all")
        public String getPhotoUrl() {
            return this.photoUrl;
        }

        @java.lang.SuppressWarnings("all")
        public com.fooddelivery.common.enums.VehicleClass getVehicleType() {
            return this.vehicleType;
        }

        @java.lang.SuppressWarnings("all")
        public void setFullName(final String fullName) {
            this.fullName = fullName;
        }

        @java.lang.SuppressWarnings("all")
        public void setPhoneNumber(final String phoneNumber) {
            this.phoneNumber = phoneNumber;
        }

        @java.lang.SuppressWarnings("all")
        public void setVehicleNumber(final String vehicleNumber) {
            this.vehicleNumber = vehicleNumber;
        }

        @java.lang.SuppressWarnings("all")
        public void setPhotoUrl(final String photoUrl) {
            this.photoUrl = photoUrl;
        }

        @java.lang.SuppressWarnings("all")
        public void setVehicleType(final com.fooddelivery.common.enums.VehicleClass vehicleType) {
            this.vehicleType = vehicleType;
        }

        @java.lang.Override
        @java.lang.SuppressWarnings("all")
        public boolean equals(final java.lang.Object o) {
            if (o == this) return true;
            if (!(o instanceof DeliveryExecutiveController.DeliveryOnboardRequest)) return false;
            final DeliveryExecutiveController.DeliveryOnboardRequest other = (DeliveryExecutiveController.DeliveryOnboardRequest) o;
            if (!other.canEqual((java.lang.Object) this)) return false;
            final java.lang.Object this$fullName = this.getFullName();
            final java.lang.Object other$fullName = other.getFullName();
            if (this$fullName == null ? other$fullName != null : !this$fullName.equals(other$fullName)) return false;
            final java.lang.Object this$phoneNumber = this.getPhoneNumber();
            final java.lang.Object other$phoneNumber = other.getPhoneNumber();
            if (this$phoneNumber == null ? other$phoneNumber != null : !this$phoneNumber.equals(other$phoneNumber)) return false;
            final java.lang.Object this$vehicleNumber = this.getVehicleNumber();
            final java.lang.Object other$vehicleNumber = other.getVehicleNumber();
            if (this$vehicleNumber == null ? other$vehicleNumber != null : !this$vehicleNumber.equals(other$vehicleNumber)) return false;
            final java.lang.Object this$photoUrl = this.getPhotoUrl();
            final java.lang.Object other$photoUrl = other.getPhotoUrl();
            if (this$photoUrl == null ? other$photoUrl != null : !this$photoUrl.equals(other$photoUrl)) return false;
            final java.lang.Object this$vehicleType = this.getVehicleType();
            final java.lang.Object other$vehicleType = other.getVehicleType();
            if (this$vehicleType == null ? other$vehicleType != null : !this$vehicleType.equals(other$vehicleType)) return false;
            return true;
        }

        @java.lang.SuppressWarnings("all")
        protected boolean canEqual(final java.lang.Object other) {
            return other instanceof DeliveryExecutiveController.DeliveryOnboardRequest;
        }

        @java.lang.Override
        @java.lang.SuppressWarnings("all")
        public int hashCode() {
            final int PRIME = 59;
            int result = 1;
            final java.lang.Object $fullName = this.getFullName();
            result = result * PRIME + ($fullName == null ? 43 : $fullName.hashCode());
            final java.lang.Object $phoneNumber = this.getPhoneNumber();
            result = result * PRIME + ($phoneNumber == null ? 43 : $phoneNumber.hashCode());
            final java.lang.Object $vehicleNumber = this.getVehicleNumber();
            result = result * PRIME + ($vehicleNumber == null ? 43 : $vehicleNumber.hashCode());
            final java.lang.Object $photoUrl = this.getPhotoUrl();
            result = result * PRIME + ($photoUrl == null ? 43 : $photoUrl.hashCode());
            final java.lang.Object $vehicleType = this.getVehicleType();
            result = result * PRIME + ($vehicleType == null ? 43 : $vehicleType.hashCode());
            return result;
        }

        @java.lang.Override
        @java.lang.SuppressWarnings("all")
        public java.lang.String toString() {
            return "DeliveryExecutiveController.DeliveryOnboardRequest(fullName=" + this.getFullName() + ", phoneNumber=" + this.getPhoneNumber() + ", vehicleNumber=" + this.getVehicleNumber() + ", photoUrl=" + this.getPhotoUrl() + ", vehicleType=" + this.getVehicleType() + ")";
        }
    }


    public static class ToggleStatusRequest {
        @NotBlank
        @Size(max = 36)
        @Pattern(regexp = "^[0-9a-fA-F\\-]{36}$")
        private String driverId;
        @NotNull
        private Boolean available;

        @java.lang.SuppressWarnings("all")
        public ToggleStatusRequest() {
        }

        @java.lang.SuppressWarnings("all")
        public String getDriverId() {
            return this.driverId;
        }

        @java.lang.SuppressWarnings("all")
        public Boolean getAvailable() {
            return this.available;
        }

        @java.lang.SuppressWarnings("all")
        public void setDriverId(final String driverId) {
            this.driverId = driverId;
        }

        @java.lang.SuppressWarnings("all")
        public void setAvailable(final Boolean available) {
            this.available = available;
        }

        @java.lang.Override
        @java.lang.SuppressWarnings("all")
        public boolean equals(final java.lang.Object o) {
            if (o == this) return true;
            if (!(o instanceof DeliveryExecutiveController.ToggleStatusRequest)) return false;
            final DeliveryExecutiveController.ToggleStatusRequest other = (DeliveryExecutiveController.ToggleStatusRequest) o;
            if (!other.canEqual((java.lang.Object) this)) return false;
            final java.lang.Object this$available = this.getAvailable();
            final java.lang.Object other$available = other.getAvailable();
            if (this$available == null ? other$available != null : !this$available.equals(other$available)) return false;
            final java.lang.Object this$driverId = this.getDriverId();
            final java.lang.Object other$driverId = other.getDriverId();
            if (this$driverId == null ? other$driverId != null : !this$driverId.equals(other$driverId)) return false;
            return true;
        }

        @java.lang.SuppressWarnings("all")
        protected boolean canEqual(final java.lang.Object other) {
            return other instanceof DeliveryExecutiveController.ToggleStatusRequest;
        }

        @java.lang.Override
        @java.lang.SuppressWarnings("all")
        public int hashCode() {
            final int PRIME = 59;
            int result = 1;
            final java.lang.Object $available = this.getAvailable();
            result = result * PRIME + ($available == null ? 43 : $available.hashCode());
            final java.lang.Object $driverId = this.getDriverId();
            result = result * PRIME + ($driverId == null ? 43 : $driverId.hashCode());
            return result;
        }

        @java.lang.Override
        @java.lang.SuppressWarnings("all")
        public java.lang.String toString() {
            return "DeliveryExecutiveController.ToggleStatusRequest(driverId=" + this.getDriverId() + ", available=" + this.getAvailable() + ")";
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

        @java.lang.SuppressWarnings("all")
        public UpdateOrderStatusRequest() {
        }

        @java.lang.SuppressWarnings("all")
        public DeliveryStatus getStatus() {
            return this.status;
        }

        @java.lang.SuppressWarnings("all")
        public String getPickupOtp() {
            return this.pickupOtp;
        }

        @java.lang.SuppressWarnings("all")
        public String getDeliveryOtp() {
            return this.deliveryOtp;
        }

        @java.lang.SuppressWarnings("all")
        public Boolean getGoOfflineAfter() {
            return this.goOfflineAfter;
        }

        @java.lang.SuppressWarnings("all")
        public void setStatus(final DeliveryStatus status) {
            this.status = status;
        }

        @java.lang.SuppressWarnings("all")
        public void setPickupOtp(final String pickupOtp) {
            this.pickupOtp = pickupOtp;
        }

        @java.lang.SuppressWarnings("all")
        public void setDeliveryOtp(final String deliveryOtp) {
            this.deliveryOtp = deliveryOtp;
        }

        @java.lang.SuppressWarnings("all")
        public void setGoOfflineAfter(final Boolean goOfflineAfter) {
            this.goOfflineAfter = goOfflineAfter;
        }

        @java.lang.Override
        @java.lang.SuppressWarnings("all")
        public boolean equals(final java.lang.Object o) {
            if (o == this) return true;
            if (!(o instanceof DeliveryExecutiveController.UpdateOrderStatusRequest)) return false;
            final DeliveryExecutiveController.UpdateOrderStatusRequest other = (DeliveryExecutiveController.UpdateOrderStatusRequest) o;
            if (!other.canEqual((java.lang.Object) this)) return false;
            final java.lang.Object this$goOfflineAfter = this.getGoOfflineAfter();
            final java.lang.Object other$goOfflineAfter = other.getGoOfflineAfter();
            if (this$goOfflineAfter == null ? other$goOfflineAfter != null : !this$goOfflineAfter.equals(other$goOfflineAfter)) return false;
            final java.lang.Object this$status = this.getStatus();
            final java.lang.Object other$status = other.getStatus();
            if (this$status == null ? other$status != null : !this$status.equals(other$status)) return false;
            final java.lang.Object this$pickupOtp = this.getPickupOtp();
            final java.lang.Object other$pickupOtp = other.getPickupOtp();
            if (this$pickupOtp == null ? other$pickupOtp != null : !this$pickupOtp.equals(other$pickupOtp)) return false;
            final java.lang.Object this$deliveryOtp = this.getDeliveryOtp();
            final java.lang.Object other$deliveryOtp = other.getDeliveryOtp();
            if (this$deliveryOtp == null ? other$deliveryOtp != null : !this$deliveryOtp.equals(other$deliveryOtp)) return false;
            return true;
        }

        @java.lang.SuppressWarnings("all")
        protected boolean canEqual(final java.lang.Object other) {
            return other instanceof DeliveryExecutiveController.UpdateOrderStatusRequest;
        }

        @java.lang.Override
        @java.lang.SuppressWarnings("all")
        public int hashCode() {
            final int PRIME = 59;
            int result = 1;
            final java.lang.Object $goOfflineAfter = this.getGoOfflineAfter();
            result = result * PRIME + ($goOfflineAfter == null ? 43 : $goOfflineAfter.hashCode());
            final java.lang.Object $status = this.getStatus();
            result = result * PRIME + ($status == null ? 43 : $status.hashCode());
            final java.lang.Object $pickupOtp = this.getPickupOtp();
            result = result * PRIME + ($pickupOtp == null ? 43 : $pickupOtp.hashCode());
            final java.lang.Object $deliveryOtp = this.getDeliveryOtp();
            result = result * PRIME + ($deliveryOtp == null ? 43 : $deliveryOtp.hashCode());
            return result;
        }

        @java.lang.Override
        @java.lang.SuppressWarnings("all")
        public java.lang.String toString() {
            return "DeliveryExecutiveController.UpdateOrderStatusRequest(status=" + this.getStatus() + ", pickupOtp=" + this.getPickupOtp() + ", deliveryOtp=" + this.getDeliveryOtp() + ", goOfflineAfter=" + this.getGoOfflineAfter() + ")";
        }
    }

    @PostMapping("/onboard")
    public ResponseEntity<ApiResponse<com.fooddelivery.delivery.entity.DeliveryExecutive>> onboardDriver(java.security.Principal principal, @Valid @RequestBody DeliveryOnboardRequest request) {
        String fullName = request.getFullName();
        String phoneNumber = request.getPhoneNumber();
        String vehicleNumber = request.getVehicleNumber();
        String photoUrl = request.getPhotoUrl();
        com.fooddelivery.common.enums.VehicleClass vehicleType = request.getVehicleType();
        com.fooddelivery.delivery.entity.DeliveryExecutive executive = profileService.onboard(UUID.fromString(principal.getName()), fullName, phoneNumber, vehicleNumber, photoUrl, vehicleType);
        return ResponseEntity.ok(ApiResponse.success(executive, "Delivery Executive onboarded successfully"));
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<com.fooddelivery.delivery.entity.DeliveryExecutive>> getProfile(@RequestParam("phoneNumber") String phoneNumber) {
        return profileService.findByPhoneNumber(phoneNumber).map(executive -> ResponseEntity.ok(ApiResponse.success(executive, "Profile fetched successfully"))).orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.<com.fooddelivery.delivery.entity.DeliveryExecutive>builder().success(false).message("Profile not found").build()));
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
        return ResponseEntity.ok(ApiResponse.success(java.util.Collections.singletonList(Map.of("id", pendingOrderId, "expiresAt", expiresAt)), "Pending ping retrieved"));
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
    @PreAuthorize("hasRole(\'DELIVERY\') and #driverId.toString() == authentication.principal")
    public ResponseEntity<ApiResponse<Void>> acceptOrder(@PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId) {
        try {
            orderAssignmentService.acceptOrderPing(driverId, orderId);
            return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Order accepted by driver").build());
        } catch (IllegalStateException ex) {
            return ResponseEntity.badRequest().body(ApiResponse.<Void>builder().success(false).message(ex.getMessage()).build());
        }
    }

    @PostMapping("/drivers/{driverId}/orders/{orderId}/reject")
    @PreAuthorize("hasRole(\'DELIVERY\') and #driverId.toString() == authentication.principal")
    public ResponseEntity<ApiResponse<Void>> rejectOrder(@PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId) {
        orderAssignmentService.rejectOrderPing(driverId, orderId);
        return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Order rejected").build());
    }

    @PostMapping("/drivers/{driverId}/orders/{orderId}/abort")
    @PreAuthorize("hasRole(\'DELIVERY\') and #driverId.toString() == authentication.principal")
    public ResponseEntity<ApiResponse<Void>> abortOrder(@PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId) {
        orderExecutionService.abortOrder(driverId, orderId);
        return ResponseEntity.ok(ApiResponse.<Void>builder().success(true).message("Order assignment aborted. Looking for a new driver.").build());
    }

    @PostMapping("/drivers/{driverId}/orders/{orderId}/status")
    @PreAuthorize("hasRole(\'DELIVERY\') and #driverId.toString() == authentication.principal")
    public ResponseEntity<ApiResponse<Object>> updateOrderStatus(@PathVariable UUID driverId, @PathVariable UUID orderId, @Valid @RequestBody UpdateOrderStatusRequest request) {
        orderExecutionService.updateOrderStatus(driverId, orderId, request.getStatus(), request.getPickupOtp(), request.getDeliveryOtp(), request.getGoOfflineAfter());
        return ResponseEntity.ok(ApiResponse.<Object>builder().success(true).message("Order status updated").build());
    }

    @GetMapping(value = "/drivers/{driverId}/orders/{orderId}/restaurant-status-stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole(\'DELIVERY\') and #driverId.toString() == authentication.principal")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamRestaurantStatus(@PathVariable("driverId") UUID driverId, @PathVariable("orderId") UUID orderId) {
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

    @java.lang.SuppressWarnings("all")
    public DeliveryExecutiveController(final DeliveryExecutiveProfileService profileService, final OrderAssignmentService orderAssignmentService, final OrderExecutionService orderExecutionService, final org.springframework.data.redis.core.StringRedisTemplate redisTemplate, final org.springframework.data.redis.listener.RedisMessageListenerContainer redisMessageListenerContainer) {
        this.profileService = profileService;
        this.orderAssignmentService = orderAssignmentService;
        this.orderExecutionService = orderExecutionService;
        this.redisTemplate = redisTemplate;
        this.redisMessageListenerContainer = redisMessageListenerContainer;
    }
}
