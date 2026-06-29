package com.fooddelivery.delivery.config;

import com.fooddelivery.delivery.handler.TrackingWebSocketHandler;
import com.fooddelivery.delivery.websocket.LocationTrackingWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final TrackingWebSocketHandler trackingWebSocketHandler;
    private final LocationTrackingWebSocketHandler locationTrackingWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(trackingWebSocketHandler, "/ws/telemetry").setAllowedOrigins("*");
        registry.addHandler(locationTrackingWebSocketHandler, "/tracking").setAllowedOrigins("*");
    }
}
