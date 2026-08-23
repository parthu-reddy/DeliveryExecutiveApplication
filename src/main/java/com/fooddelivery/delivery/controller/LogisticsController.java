package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fooddelivery.common.client.MapsServiceClient;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/v1/logistics")
@PreAuthorize("hasRole(\'DELIVERY\')")
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class LogisticsController {
private final MapsServiceClient mapsClient;

    @GetMapping("/route")
    public ResponseEntity<?> getRoute(@RequestParam double sourceLat, @RequestParam double sourceLng, @RequestParam double destLat, @RequestParam double destLng) {
        try {
            String origin = sourceLat + "," + sourceLng;
            String destination = destLat + "," + destLng;
            Map<String, Object> response = mapsClient.getRoute(origin, destination);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to calculate route: " + e.getMessage()));
        }
    }

}
