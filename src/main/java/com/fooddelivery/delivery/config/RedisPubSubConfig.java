package com.fooddelivery.delivery.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "spring.redis.enabled", matchIfMissing = true)
public class RedisPubSubConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            com.fooddelivery.delivery.websocket.LocationTrackingWebSocketHandler handler) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        
        org.springframework.data.redis.connection.MessageListener listener = (message, pattern) -> {
            String channel = new String(message.getChannel(), java.nio.charset.StandardCharsets.UTF_8);
            String body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            handler.handleRedisPing(body, channel);
        };
        
        container.addMessageListener(listener, new org.springframework.data.redis.listener.PatternTopic("ws:driver:*"));
        return container;
    }
}
