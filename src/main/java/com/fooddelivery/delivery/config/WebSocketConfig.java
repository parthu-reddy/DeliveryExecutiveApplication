package com.fooddelivery.delivery.config;

import com.fooddelivery.delivery.handler.TrackingWebSocketHandler;
import com.fooddelivery.delivery.websocket.LocationTrackingWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final TrackingWebSocketHandler trackingWebSocketHandler;
    private final LocationTrackingWebSocketHandler locationTrackingWebSocketHandler;
    private final com.fooddelivery.common.security.WebSocketSecurityInterceptor securityInterceptor = new com.fooddelivery.common.security.WebSocketSecurityInterceptor();

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(trackingWebSocketHandler, "/api/delivery/ws/telemetry").setAllowedOriginPatterns("*").addInterceptors(securityInterceptor);
        registry.addHandler(locationTrackingWebSocketHandler, "/api/delivery/tracking").setAllowedOriginPatterns("*").addInterceptors(securityInterceptor);
    }

public WebSocketConfig(final TrackingWebSocketHandler trackingWebSocketHandler, final LocationTrackingWebSocketHandler locationTrackingWebSocketHandler) {
        this.trackingWebSocketHandler = trackingWebSocketHandler;
        this.locationTrackingWebSocketHandler = locationTrackingWebSocketHandler;
    }
}
