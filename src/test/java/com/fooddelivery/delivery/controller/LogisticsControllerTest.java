package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.client.MapsServiceClient;
import com.fooddelivery.common.dto.ApiResponse;
import com.fooddelivery.common.dto.maps.RouteResponseDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LogisticsControllerTest {

    private final MapsServiceClient maps = mock(MapsServiceClient.class);
    private final LogisticsController controller = new LogisticsController(maps);

    @Test
    void returnsTheRouteInsideTheMapsServiceWrapper_flat_withTravelSeconds() {
        RouteResponseDto route = RouteResponseDto.builder().polyline("abc").duration("14 mins")
                .steps(List.of(Map.of("duration", 300), Map.of("duration", 540))).build();
        when(maps.getRoute(anyString(), anyString())).thenReturn(ApiResponse.success(route, "ok"));

        ResponseEntity<?> res = controller.getRoute(12.9, 77.6, 12.95, 77.65);

        assertEquals(200, res.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) res.getBody();
        assertEquals("abc", body.get("polyline"));
        assertEquals(840, body.get("travelSeconds"));
    }

    @Test
    void noRouteIsABadGateway_notAnEmptySuccess() {
        when(maps.getRoute(anyString(), anyString())).thenReturn(ApiResponse.success(null, "ok"));
        assertEquals(502, controller.getRoute(12.9, 77.6, 12.95, 77.65).getStatusCode().value());
    }
}
