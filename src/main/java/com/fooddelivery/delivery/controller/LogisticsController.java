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
@PreAuthorize("hasAnyRole('DELIVERY', 'CUSTOMER', 'ADMIN')")
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class LogisticsController {
private final MapsServiceClient mapsClient;

    @GetMapping("/route")
    public ResponseEntity<?> getRoute(@RequestParam double sourceLat, @RequestParam double sourceLng, @RequestParam double destLat, @RequestParam double destLng) {
        try {
            String origin = sourceLat + "," + sourceLng;
            String destination = destLat + "," + destLng;
            // The maps service wraps the route in ApiResponse; this passed the client's all-null
            // decode straight through, so no order map ever received a polyline. The body stays
            // flat ({polyline, distance, duration, ...}) because that is what the UI reads, plus
            // travelSeconds -- driving time as a number, for arrival estimates.
            com.fooddelivery.common.dto.ApiResponse<com.fooddelivery.common.dto.maps.RouteResponseDto> wrapped = mapsClient.getRoute(origin, destination);
            com.fooddelivery.common.dto.maps.RouteResponseDto route = wrapped == null ? null : wrapped.getData();
            if (route == null || route.getPolyline() == null) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_GATEWAY)
                        .body(ApiResponse.error("No route returned by the maps service"));
            }
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("polyline", route.getPolyline());
            body.put("distance", route.getDistance());
            body.put("duration", route.getDuration());
            body.put("travelSeconds", route.travelSeconds());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to calculate route: " + e.getMessage()));
        }
    }

}
