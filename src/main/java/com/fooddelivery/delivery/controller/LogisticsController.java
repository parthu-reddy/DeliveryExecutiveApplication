package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fooddelivery.delivery.client.MapsClient;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/logistics")
@RequiredArgsConstructor
@PreAuthorize("hasRole('DELIVERY')")
@Slf4j
public class LogisticsController {

    private final MapsClient mapsClient;

    @GetMapping("/route")
    public ResponseEntity<?> getRoute(
            @RequestParam double sourceLat, @RequestParam double sourceLng,
            @RequestParam double destLat, @RequestParam double destLng) {
        
        try {
            String origin = sourceLat + "," + sourceLng;
            String destination = destLat + "," + destLng;
            ResponseEntity<Map> response = mapsClient.getRoute(origin, destination);
            return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to calculate route: " + e.getMessage()));
        }
    }
}
