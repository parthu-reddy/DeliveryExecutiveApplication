package com.fooddelivery.delivery.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@FeignClient(name = "mapsintegration", fallback = MapsClientFallback.class)
public interface MapsClient {

    @PostMapping("/api/fleet/availability")
    ResponseEntity<String> setDriverAvailability(@RequestBody Map<String, Object> request);

    @PostMapping("/api/fleet/release")
    ResponseEntity<String> releaseDriver(@RequestBody Map<String, Object> request);

    @GetMapping("/api/logistics/route")
    ResponseEntity<Map> getRoute(@RequestParam("origin") String origin, @RequestParam("destination") String destination);
}
