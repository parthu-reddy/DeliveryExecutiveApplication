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
            org.springframework.data.redis.listener.adapter.MessageListenerAdapter pingListenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(pingListenerAdapter, new org.springframework.data.redis.listener.PatternTopic("ws:driver:*"));
        return container;
    }

    @Bean
    public org.springframework.data.redis.listener.adapter.MessageListenerAdapter pingListenerAdapter(
            com.fooddelivery.delivery.websocket.LocationTrackingWebSocketHandler handler) {
        return new org.springframework.data.redis.listener.adapter.MessageListenerAdapter(handler, "handleRedisPing");
    }
}
