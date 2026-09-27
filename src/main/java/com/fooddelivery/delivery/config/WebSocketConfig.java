package com.fooddelivery.delivery.config;

import com.fooddelivery.delivery.websocket.LocationTrackingWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final LocationTrackingWebSocketHandler locationTrackingWebSocketHandler;
    private final com.fooddelivery.common.security.WebSocketSecurityInterceptor securityInterceptor = new com.fooddelivery.common.security.WebSocketSecurityInterceptor();

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(locationTrackingWebSocketHandler, "/api/delivery/tracking").setAllowedOriginPatterns("*").addInterceptors(securityInterceptor);
    }

public WebSocketConfig(final LocationTrackingWebSocketHandler locationTrackingWebSocketHandler) {
        this.locationTrackingWebSocketHandler = locationTrackingWebSocketHandler;
    }
}
