package com.fooddelivery.delivery.service;

import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.enums.DutyChangeReason;
import com.fooddelivery.delivery.service.state.DeliveryExecutiveStateFactory;
import com.fooddelivery.common.enums.VerificationStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class DeliveryExecutiveProfileService {
private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final IDeliveryExecutiveRepository repository;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final com.fooddelivery.delivery.service.duty.RiderDutyNotifier dutyNotifier;

    @org.springframework.beans.factory.annotation.Value("${app.rider.biometric-verification.enabled:true}")
    private boolean biometricVerificationEnabled;

    @Transactional
    public DeliveryExecutive onboard(UUID driverId, String fullName, String phoneNumber, String vehicleNumber, String photoUrl, com.fooddelivery.common.enums.VehicleClass vehicleType, String cityId) {
        DeliveryExecutive executive = repository.findById(driverId).orElseGet(DeliveryExecutive::new);
        if (executive.getId() == null) {
            executive.setId(driverId);
            executive.setStatus(DeliveryExecutiveStatus.OFFLINE);
        }
        executive.setFullName(fullName);
        executive.setPhoneNumber(phoneNumber);
        executive.setVehicleNumber(vehicleNumber);
        executive.setPhotoUrl(photoUrl);
        executive.setCityId(cityId);
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

    @Transactional(readOnly = true)
    public java.util.Optional<DeliveryExecutive> findById(UUID id) {
        return repository.findById(id);
    }

    /**
     * Puts the rider on duty at the location their device just reported.
     *
     * <p>The fix is required. Dispatch finds riders by intersecting {@code drivers:geo:{city}} with
     * {@code drivers:available:{city}}; a rider who went online without one was ONLINE in the
     * database, absent from the geo index, and therefore never offered a trip -- until the sweeper
     * demoted them a minute later while their screen still said "Online Duty". The city is required
     * for the same reason: without it none of those keys can be written at all.
     */
    public DeliveryExecutive goOnline(UUID driverId, Double lat, Double lng) {
        if (lat == null || lng == null) {
            throw new IllegalArgumentException("Location required to go online: allow location access and try again.");
        }
        DeliveryExecutive updated = transactionTemplate.execute(status -> {
            DeliveryExecutive executive = repository.findById(driverId).orElseThrow(() -> new RuntimeException("Driver not found"));
            if (executive.getVehicleNumber() == null || executive.getVehicleNumber().trim().isEmpty()) {
                throw new IllegalArgumentException("Registration incomplete: Please complete registration before going online.");
            }
            if (executive.getCityId() == null || executive.getCityId().isBlank()) {
                throw new IllegalArgumentException("Registration incomplete: No operating city on your profile.");
            }
            if (executive.getVerificationStatus() != VerificationStatus.APPROVED) {
                throw new IllegalArgumentException("Onboarding incomplete: Driver verification status is " + executive.getVerificationStatus() + ". Must be APPROVED to go online.");
            }
            if (!executive.isActive()) {
                throw new IllegalArgumentException("Account inactive: Driver account is currently deactivated or suspended.");
            }
            if (biometricVerificationEnabled) {
                if (executive.getLastBiometricVerificationAt() == null || executive.getLastBiometricVerificationAt().isBefore(java.time.Instant.now().minus(java.time.Duration.ofHours(24)))) {
                    throw new IllegalArgumentException("Biometric verification required: Please complete your daily selfie verification to go online.");
                }
            }
            if (executive.getStatus() == DeliveryExecutiveStatus.OFFLINE) {
                DeliveryExecutiveStateFactory.getState(executive.getStatus()).goOnline(executive);
                return repository.save(executive);
            }
            return executive;
        });
        String id = driverId.toString();
        String cityId = updated.getCityId();
        // Projections AFTER commit. The fix is written even when the rider was already on duty:
        // a second "go online" from a fresh device is exactly the rider re-registering where they are.
        try {
            redisTemplate.opsForGeo().add("drivers:geo:" + cityId, new org.springframework.data.geo.Point(lng, lat), id);
            redisTemplate.opsForZSet().add("driver_last_ping", id, System.currentTimeMillis());
            if (updated.getStatus() == DeliveryExecutiveStatus.ONLINE) {
                redisTemplate.opsForSet().add(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVERS_AVAILABLE + cityId, id);
            }
            redisTemplate.opsForHash().put("drivers:status", id, updated.getStatus().name());
        } catch (Exception e) {
            log.error("Failed to sync driver {} going online with Redis. DB is ONLINE; the sweeper demotes the rider if no location follows.", driverId, e);
        }
        dutyNotifier.publish(driverId, updated.getStatus(), DutyChangeReason.RIDER_REQUEST);
        log.info("Driver {} is now {}", driverId, updated.getStatus());
        return updated;
    }

    /** Takes the rider off duty. Refused while they are carrying an order. */
    public DeliveryExecutive goOffline(UUID driverId, DutyChangeReason reason) {
        DeliveryExecutive updated = transactionTemplate.execute(status -> {
            DeliveryExecutive executive = repository.findById(driverId).orElseThrow(() -> new RuntimeException("Driver not found"));
            if (executive.getStatus() == DeliveryExecutiveStatus.OFFLINE) return executive;
            if (executive.getStatus() == DeliveryExecutiveStatus.ON_DELIVERY) {
                throw new IllegalArgumentException("Cannot go offline while on delivery. Please complete the delivery first.");
            }
            DeliveryExecutiveStateFactory.getState(executive.getStatus()).goOffline(executive);
            return repository.save(executive);
        });
        String id = driverId.toString();
        try {
            if (updated.getCityId() != null) {
                redisTemplate.opsForSet().remove(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVERS_AVAILABLE + updated.getCityId(), id);
            }
            redisTemplate.opsForZSet().remove("driver_last_ping", id);
            redisTemplate.opsForHash().put("drivers:status", id, updated.getStatus().name());
        } catch (Exception e) {
            log.error("Failed to sync driver {} going offline with Redis. DB is OFFLINE; releaseDriverLock will not return them to the pool.", driverId, e);
        }
        dutyNotifier.publish(driverId, updated.getStatus(), reason);
        log.info("Driver {} is now {} ({})", driverId, updated.getStatus(), reason);
        return updated;
    }

    @Transactional(readOnly = true)
    public java.util.List<com.fooddelivery.delivery.dto.DriverLocationDTO> getAvailableDriversWithLocation(String cityId, double lat, double lng, double radiusKm) {
        if (lat == 0 && lng == 0) {
            // Fallback for legacy calls or missing params: fetch all online drivers
            java.util.List<DeliveryExecutive> onlineDrivers = repository.findByStatus(DeliveryExecutiveStatus.ONLINE);
            return fetchDriverLocations(cityId, onlineDrivers, true);
        }
        String locationKey = "drivers:geo:" + cityId;
        java.util.List<com.fooddelivery.delivery.dto.DriverLocationDTO> result = new java.util.ArrayList<>();
        try {
            org.springframework.data.geo.Circle circle = new org.springframework.data.geo.Circle(new org.springframework.data.geo.Point(lng, lat), new org.springframework.data.geo.Distance(radiusKm, org.springframework.data.geo.Metrics.KILOMETERS));
            org.springframework.data.geo.GeoResults<org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation<String>> geoResults = redisTemplate.opsForGeo().radius(locationKey, circle);
            if (geoResults != null && !geoResults.getContent().isEmpty()) {
                java.util.List<UUID> driverIds = geoResults.getContent().stream().map(res -> UUID.fromString(res.getContent().getName())).collect(java.util.stream.Collectors.toList());
                java.util.List<Object> statuses = redisTemplate.opsForHash().multiGet("drivers:status", driverIds.stream().map(UUID::toString).collect(java.util.stream.Collectors.toList()));
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
                java.util.List<DeliveryExecutive> onlineDrivers = drivers.stream().filter(d -> d.getStatus() == DeliveryExecutiveStatus.ONLINE).collect(java.util.stream.Collectors.toList());
                log.debug("getAvailableDriversWithLocation: found {} drivers in radius", onlineDrivers.size());
                return fetchDriverLocations(cityId, onlineDrivers, false);
            }
        } catch (Exception e) {
            log.error("Failed to query Redis GEORADIUS", e);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<com.fooddelivery.delivery.dto.DriverLocationDTO> getAllDriversWithLocation(String cityId, org.springframework.data.domain.Pageable pageable) {
        org.springframework.data.domain.Page<DeliveryExecutive> allDrivers = repository.findAll(pageable);
        log.debug("getAllDriversWithLocation: found {} drivers on page", allDrivers.getNumberOfElements());
        java.util.List<com.fooddelivery.delivery.dto.DriverLocationDTO> dtoList = fetchDriverLocations(cityId, allDrivers.getContent(), true);
        return new org.springframework.data.domain.PageImpl<>(dtoList, pageable, allDrivers.getTotalElements());
    }

    /**
     * Batched Redis GEOPOS lookup — fetches all driver positions in a single round-trip
     * instead of N individual calls.
     */
    private java.util.List<com.fooddelivery.delivery.dto.DriverLocationDTO> fetchDriverLocations(String cityId, java.util.List<DeliveryExecutive> drivers, boolean includeWithoutLocation) {
        java.util.List<com.fooddelivery.delivery.dto.DriverLocationDTO> result = new java.util.ArrayList<>();
        if (drivers.isEmpty()) return result;
        String locationKey = "drivers:geo:" + cityId;
        String[] memberIds = drivers.stream().map(d -> d.getId().toString()).toArray(String[]::new);
        try {
            java.util.List<org.springframework.data.geo.Point> positions = redisTemplate.opsForGeo().position(locationKey, memberIds);
            for (int i = 0; i < drivers.size(); i++) {
                DeliveryExecutive driver = drivers.get(i);
                org.springframework.data.geo.Point pos = (positions != null && i < positions.size()) ? positions.get(i) : null;
                com.fooddelivery.delivery.dto.DriverLocationDTO dto = new com.fooddelivery.delivery.dto.DriverLocationDTO(driver.getId(), driver.getFullName(), driver.getPhoneNumber(), null, null, driver.getStatus().toString());
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

    @Transactional
    public void deactivateDriver(UUID driverId) {
        DeliveryExecutive executive = repository.findById(driverId).orElse(null);
        if (executive != null) {
            executive.setActive(false);
            if (executive.getStatus() == DeliveryExecutiveStatus.ONLINE) {
                executive.setStatus(DeliveryExecutiveStatus.OFFLINE);
            }
            repository.save(executive);
            
            // Clean up Redis
            try {
                String cityId = executive.getCityId();
                if (cityId != null) {
                    String key = com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVERS_AVAILABLE + cityId;
                    redisTemplate.opsForSet().remove(key, driverId.toString());
                }
                redisTemplate.opsForZSet().remove("driver_last_ping", driverId.toString());
                redisTemplate.opsForHash().delete("drivers:status", driverId.toString());
            } catch (Exception e) {
                log.error("Failed to clean up Redis for deactivated driver {}", driverId, e);
            }
            dutyNotifier.publish(driverId, executive.getStatus(), DutyChangeReason.ACCOUNT_DEACTIVATED);
            log.info("Driver {} deactivated and removed from active tracking", driverId);
        }
    }

}
