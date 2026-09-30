package com.fooddelivery.delivery.service;

import com.fooddelivery.delivery.dto.DriverLocationDTO;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.duty.RiderDutyNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pins the SQL-before-Redis order that keeps a fleet page coherent across cities. */
class FleetCityLocationScopeTest {

    private IDeliveryExecutiveRepository repository;
    private GeoOperations<String, String> geo;
    private DeliveryExecutiveProfileService service;
    private DeliveryExecutive blrRider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(IDeliveryExecutiveRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        geo = mock(GeoOperations.class);
        when(redis.opsForGeo()).thenReturn(geo);
        service = new DeliveryExecutiveProfileService(mock(org.springframework.transaction.support.TransactionTemplate.class),
                repository, redis, mock(RiderDutyNotifier.class));

        blrRider = new DeliveryExecutive();
        blrRider.setId(UUID.randomUUID());
        blrRider.setCityId("BLR");
        blrRider.setFullName("Bengaluru rider");
        blrRider.setStatus(DeliveryExecutiveStatus.ONLINE);
    }

    @Test
    void scopesTheDatabasePageBeforeReadingTheCityGeoKey() {
        var pageable = PageRequest.of(0, 100);
        when(repository.findByCityId("BLR", pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(blrRider), pageable, 1));
        when(geo.position(eq("drivers:geo:BLR"), any(String[].class)))
                .thenReturn(List.of(new Point(77.5946, 12.9716)));

        var page = service.getAllDriversWithLocation("BLR", pageable);

        assertThat(page.getContent()).extracting(DriverLocationDTO::getId).containsExactly(blrRider.getId());
        assertThat(page.getContent()).allSatisfy(rider -> {
            assertThat(rider.getLat()).isNotZero();
            assertThat(rider.getLng()).isNotZero();
        });
        verify(repository).findByCityId("BLR", pageable);
        verify(repository, never()).findAll(any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void neverTurnsAMissingLocationIntoAFabricatedZeroCoordinate() {
        var pageable = PageRequest.of(0, 100);
        when(repository.findByCityId("BLR", pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(blrRider), pageable, 1));
        // List.of rejects null while Redis is permitted to return a null point for a member
        // that disappeared between the database page and the geo lookup.
        when(geo.position(anyString(), any(String[].class))).thenReturn(Collections.singletonList(null));

        var page = service.getAllDriversWithLocation("BLR", pageable);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void legacyAvailabilityFallbackUsesTheSameCityPredicate() {
        when(repository.findByCityIdAndStatus("BLR", DeliveryExecutiveStatus.ONLINE)).thenReturn(List.of());

        assertThat(service.getAvailableDriversWithLocation("BLR", 0, 0, 50)).isEmpty();

        verify(repository).findByCityIdAndStatus("BLR", DeliveryExecutiveStatus.ONLINE);
        verify(repository, never()).findByStatus(DeliveryExecutiveStatus.ONLINE);
    }
}
