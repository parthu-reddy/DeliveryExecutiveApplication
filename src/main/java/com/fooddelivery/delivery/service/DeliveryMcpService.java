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

@Service
public class DeliveryMcpService {

    private final DeliveryExecutiveController deliveryController;
    private final DeliveryTelemetryController telemetryController;
    private final LogisticsController logisticsController;
    private final ObjectMapper objectMapper;

    public DeliveryMcpService(DeliveryExecutiveController deliveryController,
                              DeliveryTelemetryController telemetryController,
                              LogisticsController logisticsController,
                              ObjectMapper objectMapper) {
        this.deliveryController = deliveryController;
        this.telemetryController = telemetryController;
        this.logisticsController = logisticsController;
        this.objectMapper = objectMapper;
    }

    private Principal createMockPrincipal(String driverId) {
        return () -> driverId;
    }

    @Tool(description = "Onboard a new delivery executive. Provide driverId (representing X-User-Id), name, phoneNumber, vehicleNumber, and photoUrl.")
    public String onboardDriver(String driverId, String name, String phoneNumber, String vehicleNumber, String photoUrl) {
        try {
            DeliveryExecutiveController.DeliveryOnboardRequest req = new DeliveryExecutiveController.DeliveryOnboardRequest();
            req.setName(name);
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
            Map<String, Object> req = new HashMap<>();
            req.put("driverId", driverId);
            req.put("available", isOnline);
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
            Map<String, String> req = new HashMap<>();
            req.put("status", status);
            return objectMapper.writeValueAsString(deliveryController.updateOrderStatus(UUID.fromString(driverId), UUID.fromString(orderId), req).getBody());
        } catch (Exception e) {
            return "Failed to update order status: " + e.getMessage();
        }
    }

    @Tool(description = "Timeout a driver ping for an order. Provide driverId and orderId.")
    public String timeoutDriverPing(String driverId, String orderId) {
        try {
            return objectMapper.writeValueAsString(deliveryController.timeoutDriver(UUID.fromString(driverId), UUID.fromString(orderId)).getBody());
        } catch (Exception e) {
            return "Failed to timeout driver ping: " + e.getMessage();
        }
    }

    @Tool(description = "Process batch telemetry for drivers. Provide JSON string of list of telemetry events.")
    public String processBatchTelemetry(String telemetryBatchJson) {
        try {
            List<Map<String, Object>> batch = objectMapper.readValue(telemetryBatchJson, new TypeReference<List<Map<String, Object>>>() {});
            String authId = batch.isEmpty() ? "mock-driver-id" : (String) batch.get(0).get("driverId");
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
}
