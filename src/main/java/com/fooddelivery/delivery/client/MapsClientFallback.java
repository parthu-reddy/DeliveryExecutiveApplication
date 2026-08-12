package com.fooddelivery.delivery.client;

import org.springframework.stereotype.Component;
import org.springframework.http.ResponseEntity;
import java.util.Map;

@Component("deliveryexecutiveMapsClientFallback")
public class MapsClientFallback implements MapsClient {
    @Override
    public ResponseEntity<String> setDriverAvailability(Map<String, Object> request) {
        throw new IllegalStateException("Maps service is currently unavailable.");
    }

    @Override
    public ResponseEntity<String> releaseDriver(Map<String, Object> request) {
        throw new IllegalStateException("Maps service is currently unavailable.");
    }

    @Override
    public ResponseEntity<Map> getRoute(String origin, String destination) {
        throw new IllegalStateException("Maps service is currently unavailable.");
    }
}
