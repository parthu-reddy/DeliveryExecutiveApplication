package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.client.MapsServiceClient;
import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.common.dto.maps.RouteResponseDto;
import com.fooddelivery.delivery.dto.RouteDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LogisticsControllerTest {

    private final MapsServiceClient maps = mock(MapsServiceClient.class);
    private final LogisticsController controller = new LogisticsController(maps);

    @Test
    void returnsTheRouteFlat_withTheProvidersNumericTotals() {
        // Live values observed 2026-10-07 for a rider-to-restaurant leg.
        RouteResponseDto route = RouteResponseDto.builder().polyline("abc").distance("4.58")
                .duration("0 hours 16 minutes").durationSeconds(939).distanceMeters(4577).build();
        when(maps.getRoute(eq("12.9,77.6"), eq("12.95,77.65"))).thenReturn(ApiResponse.success(route, "ok"));

        ResponseEntity<RouteDto> res = controller.getRoute(12.9, 77.6, 12.95, 77.65);

        assertEquals(200, res.getStatusCode().value());
        assertEquals(new RouteDto("abc", "4.58", "0 hours 16 minutes", 939, 4577), res.getBody());
    }

    @Test
    void totalsTheProviderDidNotSendStayNull() {
        when(maps.getRoute(anyString(), anyString())).thenReturn(ApiResponse.success(
                RouteResponseDto.builder().polyline("abc").duration("0 hours 16 minutes").build(), "ok"));
        RouteDto body = controller.getRoute(12.9, 77.6, 12.95, 77.65).getBody();
        assertNull(body.durationSeconds());
        assertNull(body.distanceMeters());
    }

    @Test
    void noRouteIsABadGateway_notAnEmptySuccess() {
        when(maps.getRoute(anyString(), anyString())).thenReturn(ApiResponse.success(null, "ok"));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.getRoute(12.9, 77.6, 12.95, 77.65));
        assertEquals(502, e.getStatusCode().value());
    }
}
