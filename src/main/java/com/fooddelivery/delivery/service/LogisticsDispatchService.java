package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.KafkaConstants;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fooddelivery.common.client.MapsServiceClient;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class LogisticsDispatchService {
private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MapsServiceClient mapsClient;
    private final IDeliveryExecutiveRepository repository;

    public void dispatchNearestDriver(String dispatchCityId, double fleetSearchRadiusKm, double restaurantLat, double restaurantLng, double deliveryLat, double deliveryLng, String deliveryAddress, UUID orderId, java.util.List<String> excludedDriverIds) {
        log.info("LOGISTICS_DISPATCH_STARTED orderId={} dispatchCityId={} fleetSearchRadiusKm={} excludedDriverCount={}",
                orderId, dispatchCityId, fleetSearchRadiusKm,
                excludedDriverIds == null ? 0 : excludedDriverIds.size());
        try {
            com.fooddelivery.common.event.DispatchRequestedEvent dispatchRequest =
                    com.fooddelivery.common.event.DispatchRequestedEvent.builder()
                            .orderId(orderId.toString())
                            .dispatchCityId(dispatchCityId)
                            .fleetSearchRadiusKm(fleetSearchRadiusKm)
                            .restaurantLat(restaurantLat)
                            .restaurantLng(restaurantLng)
                            .deliveryLat(deliveryLat)
                            .deliveryLng(deliveryLng)
                            .deliveryAddress(deliveryAddress)
                            .excludedDriverIds(excludedDriverIds != null ? excludedDriverIds : java.util.Collections.emptyList())
                            .build();
            String payload = objectMapper.writeValueAsString(dispatchRequest);
            UUID eventId = UUID.randomUUID();
            // Deliberately synchronous (bypassing outbox) to minimize latency for driver dispatch
            org.springframework.messaging.Message<String> message = org.springframework.messaging.support.MessageBuilder
                .withPayload(payload)
                .setHeader(org.springframework.kafka.support.KafkaHeaders.TOPIC, KafkaConstants.TOPIC_LOGISTICS_DISPATCH)
                .setHeader(org.springframework.kafka.support.KafkaHeaders.KEY, orderId.toString())
                .setHeader("eventId", eventId.toString())
                .build();
            kafkaTemplate.send(message).get(3, java.util.concurrent.TimeUnit.SECONDS);
            log.info("LOGISTICS_DISPATCH_PUBLISHED eventId={} orderId={} topic={} dispatchCityId={} fleetSearchRadiusKm={}",
                    eventId, orderId, KafkaConstants.TOPIC_LOGISTICS_DISPATCH, dispatchCityId, fleetSearchRadiusKm);
        } catch (Exception e) {
            log.error("LOGISTICS_DISPATCH_FAILED orderId={} dispatchCityId={} errorType={} error={}",
                    orderId, dispatchCityId, e.getClass().getSimpleName(), e.getMessage(), e);
            throw new RuntimeException("Failed to publish dispatch request", e);
        }
    }

    public void releaseDriverLock(String driverId) {
        log.info("Requesting driver lock release for driver {} via MapsIntegration service", driverId);
        DeliveryExecutive executive = repository.findById(UUID.fromString(driverId)).orElse(null);
        String cityId = executive == null ? null : executive.getCityId();
        if (cityId == null) {
            log.warn("Cannot release driver lock for driver {}: cityId is null", driverId);
            return;
        }
        // Releasing means "back into drivers:available", the set dispatch offers trips from. Every
        // caller -- ping timeout, decline, abort, delivered/failed with "go offline after",
        // cancellation -- used to do that unconditionally, so a rider who had gone offline (or been
        // swept offline) while a ping to them was pending came back as dispatchable, and their
        // still-running telemetry kept them in the geo index.
        if (executive.getStatus() != com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.ONLINE) {
            log.info("DRIVER_RELEASE_SKIPPED driverId={} status={}: only ONLINE riders return to the pool",
                    driverId, executive.getStatus());
            return;
        }
        try {
            com.fooddelivery.common.dto.maps.SetAvailabilityRequest request = new com.fooddelivery.common.dto.maps.SetAvailabilityRequest();
            request.setCityId(cityId);
            request.setDriverId(driverId);
            request.setAvailable(true);
            log.info("Sending request to MapsIntegration /api/fleet/release: {}", request);
            java.util.Map<String, Object> response = mapsClient.releaseDriver(request);
            log.info("Successfully requested driver lock release for driver {}. Response: {}", driverId, response);
        } catch (Exception e) {
            log.error("Failed to release driver lock for driver {} via FeignClient. Error: {}", driverId, e.getMessage(), e);
            throw new RuntimeException("Failed to release driver lock", e);
        }
    }

    /**
     * The inverse of {@link #releaseDriverLock(String)}: takes the driver back out of the pool.
     *
     * <p>Used when a decline could not be recorded. {@code rejectOrderPing} releases the driver
     * before writing the outbox event, so if that write fails it restores the pending ping -- but
     * the release had already advertised the driver as free. Without this the two halves disagree:
     * the driver holds a ping for an order they are also available to be re-offered.
     *
     * <p>Goes through {@code /api/fleet/availability}, not {@code /api/fleet/release}, because the
     * latter ignores the {@code available} flag and always releases.
     */
    public void reserveDriverLock(String driverId) {
        log.info("Re-reserving driver {} via MapsIntegration after a failed decline", driverId);
        String cityId = repository.findById(UUID.fromString(driverId)).map(DeliveryExecutive::getCityId).orElse(null);
        if (cityId == null) {
            log.warn("Cannot re-reserve driver {}: cityId is null", driverId);
            return;
        }
        try {
            com.fooddelivery.common.dto.maps.SetAvailabilityRequest request = new com.fooddelivery.common.dto.maps.SetAvailabilityRequest();
            request.setCityId(cityId);
            request.setDriverId(driverId);
            request.setAvailable(false);
            mapsClient.setDriverAvailability(request);
            log.info("Driver {} re-reserved in city {}", driverId, cityId);
        } catch (Exception e) {
            log.error("Failed to re-reserve driver {} via FeignClient. Error: {}", driverId, e.getMessage(), e);
            throw new RuntimeException("Failed to re-reserve driver", e);
        }
    }

}
