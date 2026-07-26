package com.fooddelivery.delivery.service;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.common.enums.VerificationStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryExecutiveProfileService {

    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final IDeliveryExecutiveRepository repository;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Transactional
    public DeliveryExecutive onboard(UUID driverId, String fullName, String phoneNumber, String vehicleNumber, String photoUrl, com.fooddelivery.common.enums.VehicleClass vehicleType) {
        DeliveryExecutive executive = repository.findById(driverId).orElseGet(DeliveryExecutive::new);
        
        if (executive.getId() == null) {
            executive.setId(driverId);
            executive.setStatus(DeliveryExecutiveStatus.OFFLINE);
        }
        
        executive.setFullName(fullName);
        executive.setPhoneNumber(phoneNumber);
        executive.setVehicleNumber(vehicleNumber);
        executive.setPhotoUrl(photoUrl);
        if (vehicleType != null) {
            executive.setVehicleType(vehicleType);
        }
        // createdAt and updatedAt are auto-managed by @CreationTimestamp/@UpdateTimestamp
        
        log.info("Onboarded/Updated Delivery Executive: {}", executive.getId());
        return repository.save(executive);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<DeliveryExecutive> findByPhoneNumber(String phoneNumber) {
        return repository.findByPhoneNumber(phoneNumber);
    }

    public DeliveryExecutive toggleStatus(UUID driverId, boolean isOnline) {
        // Enforce Biometric freshness check BEFORE starting transaction if possible, 
        // but we need to fetch the executive first. We will do it inside.
        
        DeliveryExecutive updated = transactionTemplate.execute(status -> {
            DeliveryExecutive executive = repository.findById(driverId)
                    .orElseThrow(() -> new RuntimeException("Driver not found"));
            
            if (isOnline && (executive.getVehicleNumber() == null || executive.getVehicleNumber().trim().isEmpty())) {
                throw new IllegalArgumentException("Registration incomplete: Please complete registration before going online.");
            }
            if (isOnline && executive.getVerificationStatus() != VerificationStatus.APPROVED) {
                throw new IllegalArgumentException("Onboarding incomplete: Driver verification status is " + executive.getVerificationStatus() + ". Must be APPROVED to go online.");
            }
            if (isOnline && !executive.isActive()) {
                throw new IllegalArgumentException("Account inactive: Driver account is currently deactivated or suspended.");
            }
            if (isOnline) {
                if (executive.getLastBiometricVerificationAt() == null || 
                    executive.getLastBiometricVerificationAt().isBefore(java.time.OffsetDateTime.now().minusHours(24))) {
                    throw new IllegalArgumentException("Biometric verification required: Please complete your daily selfie verification to go online.");
                }
            }
            
            if (isOnline && (executive.getStatus() == DeliveryExecutiveStatus.ONLINE || executive.getStatus() == DeliveryExecutiveStatus.ON_DELIVERY)) return executive;
            if (!isOnline && executive.getStatus() == DeliveryExecutiveStatus.OFFLINE) return executive;
            if (!isOnline && executive.getStatus() == DeliveryExecutiveStatus.ON_DELIVERY) {
                throw new IllegalArgumentException("Cannot go offline while on delivery. Please complete the delivery first.");
            }

            com.fooddelivery.delivery.service.state.DeliveryExecutiveState state = com.fooddelivery.delivery.service.state.DeliveryExecutiveStateFactory.getState(executive.getStatus());
            if (isOnline) {
                state.goOnline(executive);
            } else {
                state.goOffline(executive);
            }
            
            return repository.save(executive);
        });
        
        // Sync with Redis AFTER the transaction has successfully committed
        String key = "drivers:available:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;
        try {
            if (isOnline) {
                redisTemplate.opsForSet().add(key, driverId.toString());
                redisTemplate.opsForZSet().add("driver_last_ping", driverId.toString(), System.currentTimeMillis());
            } else {
                redisTemplate.opsForSet().remove(key, driverId.toString());
                redisTemplate.opsForZSet().remove("driver_last_ping", driverId.toString());
            }
            redisTemplate.opsForHash().put("drivers:status", driverId.toString(), updated.getStatus().name());
        } catch (Exception e) {
            log.error("Failed to sync driver status with Redis for driver {}. DB was updated, but Redis might be inconsistent.", driverId, e);
            // Ideally trigger a retry or reconciliation job, but DB state is consistent.
        }
        
        log.info("Driver {} is now {}", driverId, updated.getStatus());
        return updated;
    }

    @Transactional(readOnly = true)
    public java.util.List<com.fooddelivery.delivery.dto.DriverLocationDTO> getAvailableDriversWithLocation(double lat, double lng, double radiusKm) {
        if (lat == 0 && lng == 0) {
            // Fallback for legacy calls or missing params: fetch a limited set or empty
            return new java.util.ArrayList<>();
        }

        String locationKey = "drivers:geo:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;
        java.util.List<com.fooddelivery.delivery.dto.DriverLocationDTO> result = new java.util.ArrayList<>();

        try {
            org.springframework.data.geo.Circle circle = new org.springframework.data.geo.Circle(
                new org.springframework.data.geo.Point(lng, lat),
                new org.springframework.data.geo.Distance(radiusKm, org.springframework.data.geo.Metrics.KILOMETERS)
            );
            org.springframework.data.geo.GeoResults<org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation<String>> geoResults = 
                redisTemplate.opsForGeo().radius(locationKey, circle);

            if (geoResults != null && !geoResults.getContent().isEmpty()) {
                java.util.List<UUID> driverIds = geoResults.getContent().stream()
                    .map(res -> UUID.fromString(res.getContent().getName()))
                    .collect(java.util.stream.Collectors.toList());
                
                java.util.List<Object> statuses = redisTemplate.opsForHash().multiGet("drivers:status", 
                    driverIds.stream().map(UUID::toString).collect(java.util.stream.Collectors.toList()));
                
                java.util.List<UUID> onlineDriverIds = new java.util.ArrayList<>();
                for (int i = 0; i < driverIds.size(); i++) {
                    if (statuses != null && i < statuses.size() && statuses.get(i) != null && "ONLINE".equals(statuses.get(i).toString())) {
                        onlineDriverIds.add(driverIds.get(i));
                    }
                }
                
                if (onlineDriverIds.isEmpty()) {
                    return result;
                }
                
                java.util.List<DeliveryExecutive> drivers = repository.findAllById(onlineDriverIds);
                // Filter to ensure we only return ONLINE drivers as requested (double check against DB)
                java.util.List<DeliveryExecutive> onlineDrivers = drivers.stream()
                    .filter(d -> d.getStatus() == DeliveryExecutiveStatus.ONLINE)
                    .collect(java.util.stream.Collectors.toList());
                    
                log.debug("getAvailableDriversWithLocation: found {} drivers in radius", onlineDrivers.size());
                return fetchDriverLocations(onlineDrivers, false);
            }
        } catch (Exception e) {
            log.error("Failed to query Redis GEORADIUS", e);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public java.util.List<com.fooddelivery.delivery.dto.DriverLocationDTO> getAllDriversWithLocation() {
        java.util.List<DeliveryExecutive> allDrivers = repository.findAll();
        log.debug("getAllDriversWithLocation: found {} drivers", allDrivers.size());
        return fetchDriverLocations(allDrivers, true);
    }

    /**
     * Batched Redis GEOPOS lookup — fetches all driver positions in a single round-trip
     * instead of N individual calls.
     */
    private java.util.List<com.fooddelivery.delivery.dto.DriverLocationDTO> fetchDriverLocations(
            java.util.List<DeliveryExecutive> drivers, boolean includeWithoutLocation) {
        java.util.List<com.fooddelivery.delivery.dto.DriverLocationDTO> result = new java.util.ArrayList<>();
        if (drivers.isEmpty()) return result;

        String locationKey = "drivers:geo:" + com.fooddelivery.common.constants.AppConstants.DEFAULT_CITY_ID;
        String[] memberIds = drivers.stream().map(d -> d.getId().toString()).toArray(String[]::new);

        try {
            java.util.List<org.springframework.data.geo.Point> positions = redisTemplate.opsForGeo().position(locationKey, memberIds);
            for (int i = 0; i < drivers.size(); i++) {
                DeliveryExecutive driver = drivers.get(i);
                org.springframework.data.geo.Point pos = (positions != null && i < positions.size()) ? positions.get(i) : null;
                com.fooddelivery.delivery.dto.DriverLocationDTO dto = new com.fooddelivery.delivery.dto.DriverLocationDTO(
                    driver.getId(), driver.getFullName(), driver.getPhoneNumber(), null, null, driver.getStatus().toString()
                );
                if (pos != null) {
                    dto.setLng(pos.getX());
                    dto.setLat(pos.getY());
                    result.add(dto);
                } else if (includeWithoutLocation) {
                    dto.setLat(0.0);
                    dto.setLng(0.0);
                    result.add(dto);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to batch fetch driver locations from Redis", e);
        }
        return result;
    }
}
