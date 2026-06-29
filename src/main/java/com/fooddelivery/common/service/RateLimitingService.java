package com.fooddelivery.common.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.Refill;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RateLimitingService {

    private final LettuceBasedProxyManager<String> proxyManager;

    public RateLimitingService(LettuceBasedProxyManager<String> proxyManager) {
        this.proxyManager = proxyManager;
    }

    public Bucket resolveBucket(String key, int capacity, int refillTokens, Duration refillDuration) {
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(capacity, Refill.intervally(refillTokens, refillDuration)))
                .build();

        return proxyManager.builder().build(key, configuration);
    }

    @Configuration
    public static class RateLimitConfig {

        @Value("${spring.data.redis.host:localhost}")
        private String redisHost;

        @Value("${spring.data.redis.port:6379}")
        private int redisPort;

        @Bean
        public LettuceBasedProxyManager<String> lettuceProxyManager() {
            RedisClient redisClient = RedisClient.create(RedisURI.builder()
                    .withHost(redisHost)
                    .withPort(redisPort)
                    .build());
            
            StatefulRedisConnection<String, byte[]> connection = redisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
            return LettuceBasedProxyManager.builderFor(connection)
                    .withExpirationStrategy(io.github.bucket4j.distributed.ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(10)))
                    .build();
        }
    }
}
