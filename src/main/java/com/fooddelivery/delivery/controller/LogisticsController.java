package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/logistics")
@RequiredArgsConstructor
public class LogisticsController {

    private final RestTemplate restTemplate;

    @GetMapping("/route")
    public ResponseEntity<?> getRoute(
            @RequestParam double sourceLat, @RequestParam double sourceLng,
            @RequestParam double destLat, @RequestParam double destLng) {
        
        try {
            String mapsServiceUrl = String.format("http://localhost:8083/api/logistics/route?origin=%f,%f&destination=%f,%f",
                    sourceLat, sourceLng, destLat, destLng);
            
            ResponseEntity<Map> response = restTemplate.getForEntity(mapsServiceUrl, Map.class);
            return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to calculate route: " + e.getMessage()));
        }
    }
}
