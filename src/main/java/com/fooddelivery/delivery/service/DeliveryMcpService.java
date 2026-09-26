package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.delivery.controller.DeliveryExecutiveController;
import com.fooddelivery.delivery.controller.DeliveryTelemetryController;
import com.fooddelivery.delivery.controller.LogisticsController;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;
import java.lang.reflect.Proxy;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import com.fooddelivery.common.enums.OrderStatus;

@Service
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class DeliveryMcpService {
private final DeliveryExecutiveController deliveryController;
    private final DeliveryTelemetryController telemetryController;
    private final LogisticsController logisticsController;
    private final com.fooddelivery.delivery.controller.AdminDeliveryController adminDeliveryController;
    private final com.fooddelivery.delivery.controller.DeliveryOrderController deliveryOrderController;
    private final com.fooddelivery.delivery.controller.InternalDeliveryController internalDeliveryController;
    private final com.fooddelivery.delivery.controller.DeliveryVerificationController deliveryVerificationController;
    private final ObjectMapper objectMapper;


    private Principal createMockPrincipal(String driverId) {
        return () -> driverId;
    }

    @Tool(description = "Onboard a new delivery executive. Provide driverId (representing X-User-Id), phoneNumber, vehicleNumber, and photoUrl.")
    public String onboardDriver(String driverId, String phoneNumber, String vehicleNumber, String photoUrl) {
        try {
            DeliveryExecutiveController.DeliveryOnboardRequest req = new DeliveryExecutiveController.DeliveryOnboardRequest();
            req.setPhoneNumber(phoneNumber);
            req.setVehicleNumber(vehicleNumber);
            req.setPhotoUrl(photoUrl);
            return objectMapper.writeValueAsString(deliveryController.onboardDriver(createMockPrincipal(driverId), req).getBody());
        } catch (Exception e) {
            return "Failed to onboard driver: " + e.getMessage();
        }
    }

    @Tool(description = "Toggle the online/offline status of a delivery executive. Provide driverId and boolean isOnline.")
    public String toggleDriverStatus(String driverId, boolean isOnline) {
        try {
            DeliveryExecutiveController.ToggleStatusRequest req = new DeliveryExecutiveController.ToggleStatusRequest();
            req.setDriverId(driverId);
            req.setAvailable(isOnline);
            return objectMapper.writeValueAsString(deliveryController.toggleStatus(createMockPrincipal(driverId), req).getBody());
        } catch (Exception e) {
            return "Failed to toggle driver status: " + e.getMessage();
        }
    }

    @Tool(description = "Accept an order ping assigned to a delivery executive. Provide driverId and orderId.")
    public String acceptOrderPing(String driverId, String orderId) {
        try {
            return objectMapper.writeValueAsString(deliveryController.acceptOrder(UUID.fromString(driverId), UUID.fromString(orderId)).getBody());
        } catch (Exception e) {
            return "Failed to accept order ping: " + e.getMessage();
        }
    }

    @Tool(description = "Reject an order ping assigned to a delivery executive. Provide driverId and orderId.")
    public String rejectOrderPing(String driverId, String orderId) {
        try {
            return objectMapper.writeValueAsString(deliveryController.rejectOrder(UUID.fromString(driverId), UUID.fromString(orderId)).getBody());
        } catch (Exception e) {
            return "Failed to reject order ping: " + e.getMessage();
        }
    }

    @Tool(description = "Update the status of an ongoing order delivery. Provide driverId, orderId, and status (e.g., PICKED_UP, DELIVERED).")
    public String updateOrderStatus(String driverId, String orderId, String status) {
        try {
            DeliveryExecutiveController.UpdateOrderStatusRequest req = new DeliveryExecutiveController.UpdateOrderStatusRequest();
            req.setStatus(com.fooddelivery.common.enums.DeliveryStatus.valueOf(status));
            return objectMapper.writeValueAsString(deliveryController.updateOrderStatus(UUID.fromString(driverId), UUID.fromString(orderId), req).getBody());
        } catch (Exception e) {
            return "Failed to update order status: " + e.getMessage();
        }
    }

    @Tool(description = "Process batch telemetry for drivers. Provide JSON string of list of telemetry events.")
    public String processBatchTelemetry(String telemetryBatchJson) {
        try {
            List<com.fooddelivery.delivery.dto.TelemetryEventRequest> batch = objectMapper.readValue(telemetryBatchJson, new TypeReference<List<com.fooddelivery.delivery.dto.TelemetryEventRequest>>() {
            });
            String authId = batch.isEmpty() ? "mock-driver-id" : batch.get(0).getDriverId();
            return objectMapper.writeValueAsString(telemetryController.processBatchTelemetry(createMockPrincipal(authId), batch).getBody());
        } catch (Exception e) {
            return "Failed to process batch telemetry: " + e.getMessage();
        }
    }

    @Tool(description = "Get delivery route between source and destination coordinates. Provide sourceLat, sourceLng, destLat, and destLng.")
    public String getRoute(double sourceLat, double sourceLng, double destLat, double destLng) {
        try {
            return objectMapper.writeValueAsString(logisticsController.getRoute(sourceLat, sourceLng, destLat, destLng).getBody());
        } catch (Exception e) {
            return "Failed to get route: " + e.getMessage();
        }
    }

    // AdminDeliveryController
    @Tool(description = "Admin: Get available drivers.")
    public String getAvailableDrivers() {
        try {
            return objectMapper.writeValueAsString(adminDeliveryController.getAvailableDrivers(org.springframework.data.domain.PageRequest.of(0, 100)).getBody());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Admin: Get available drivers with location. Provide cityId, lat, lng, and radiusKm.")
    public String getAvailableDriversWithLocation(String cityId, double lat, double lng, double radiusKm) {
        try {
            return objectMapper.writeValueAsString(adminDeliveryController.getAvailableDriversWithLocation(cityId, lat, lng, radiusKm).getBody());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Admin: Get all drivers with location. Provide cityId.")
    public String getAllDriversWithLocation(String cityId) {
        try {
            return objectMapper.writeValueAsString(adminDeliveryController.getAllDriversWithLocation(cityId, org.springframework.data.domain.PageRequest.of(0, 100)).getBody());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Admin: Force assign order. Provide orderId and driverId.")
    public String forceAssignOrder(String orderId, String driverId) {
        try {
            return objectMapper.writeValueAsString(adminDeliveryController.forceAssignOrder(UUID.fromString(orderId), UUID.fromString(driverId)).getBody());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Admin: Get driver by ID. Provide driverId.")
    public String getDriverById(String driverId) {
        try {
            return objectMapper.writeValueAsString(adminDeliveryController.getDriverById(UUID.fromString(driverId)).getBody());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // DeliveryOrderController
    @Tool(description = "Driver: Get active orders. Provide driverId.")
    public String getDriverActiveOrders(String driverId) {
        try {
            return objectMapper.writeValueAsString(deliveryOrderController.getActiveOrders(createMockPrincipal(driverId), 0, 100).getBody());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Driver: Get available orders. Provide driverId.")
    public String getDriverAvailableOrders(String driverId) {
        try {
            return objectMapper.writeValueAsString(deliveryOrderController.getAvailableOrders(createMockPrincipal(driverId)).getBody());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Driver: Get order history. Provide driverId and the window as ISO-8601 instants with a zone, from (inclusive) and to (exclusive), e.g. 2026-09-24T18:30:00Z.")
    public String getDriverOrderHistory(String driverId, String from, String to) {
        try {
            return objectMapper.writeValueAsString(deliveryOrderController.getOrderHistory(createMockPrincipal(driverId), java.time.Instant.parse(from), java.time.Instant.parse(to), 0, 100).getBody());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // InternalDeliveryController
    @Tool(description = "Internal: Suspend driver. Provide driverId.")
    public String suspendDriver(String driverId) {
        try {
            return objectMapper.writeValueAsString(internalDeliveryController.suspendDriver(UUID.fromString(driverId)).getBody());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // DeliveryVerificationController
    @Tool(description = "Driver: Get verification status. Provide driverId.")
    public String getDriverVerificationStatus(String driverId) {
        try {
            return objectMapper.writeValueAsString(deliveryVerificationController.getVerificationStatus(createMockPrincipal(driverId)).getBody());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Driver: Get presigned upload URL. Provide docType and contentType.")
    public String getDriverPresignedUploadUrl(String docType, String contentType) {
        try {
            return objectMapper.writeValueAsString(deliveryVerificationController.getPresignedUploadUrl(docType, contentType).getBody());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Driver: Get presigned download URL. Provide objectKey.")
    public String getDriverPresignedDownloadUrl(String objectKey) {
        try {
            return objectMapper.writeValueAsString(deliveryVerificationController.getPresignedDownloadUrl(objectKey).getBody());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
// @Getter
