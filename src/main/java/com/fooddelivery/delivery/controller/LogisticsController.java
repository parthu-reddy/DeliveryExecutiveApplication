package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fooddelivery.common.client.MapsServiceClient;
import com.fooddelivery.common.dto.maps.RouteResponseDto;
import com.fooddelivery.delivery.dto.RouteDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/v1/logistics")
@PreAuthorize("hasAnyRole('DELIVERY', 'CUSTOMER', 'ADMIN')")
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class LogisticsController {
private final MapsServiceClient mapsClient;

    @GetMapping("/route")
    public ResponseEntity<RouteDto> getRoute(@RequestParam double sourceLat, @RequestParam double sourceLng, @RequestParam double destLat, @RequestParam double destLng) {
        // The maps service wraps the route in ApiResponse; the UI reads it flat. A missing route is a
        // bad gateway, not an empty success; the client's fallback answers 503 when maps is down.
        ApiResponse<RouteResponseDto> wrapped = mapsClient.getRoute(sourceLat + "," + sourceLng, destLat + "," + destLng);
        RouteResponseDto route = wrapped == null ? null : wrapped.getData();
        if (route == null || route.getPolyline() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No route returned by the maps service");
        }
        return ResponseEntity.ok(new RouteDto(route.getPolyline(), route.getDistance(), route.getDuration(),
                route.getDurationSeconds(), route.getDistanceMeters()));
    }

}
