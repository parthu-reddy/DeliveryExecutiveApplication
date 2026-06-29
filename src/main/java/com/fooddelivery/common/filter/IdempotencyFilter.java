package com.fooddelivery.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redisTemplate;
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final Duration IDEMPOTENCY_EXPIRATION = Duration.ofHours(24);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String idempotencyKey = request.getHeader(IDEMPOTENCY_KEY_HEADER);

        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            String cacheKey = "idempotency:" + idempotencyKey;
            
            // Try to acquire lock for this key
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(cacheKey, "PROCESSING", IDEMPOTENCY_EXPIRATION);
            
            if (Boolean.FALSE.equals(acquired)) {
                log.warn("Duplicate request detected for idempotency key: {}", idempotencyKey);
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                response.getWriter().write("Duplicate request detected.");
                return;
            }

            try {
                filterChain.doFilter(request, response);
            } catch (Exception ex) {
                // Release lock on unhandled exception
                redisTemplate.delete(cacheKey);
                log.info("Released idempotency lock {} due to exception", idempotencyKey);
                throw ex;
            } finally {
                // Release lock if it's a 5xx error
                if (response.getStatus() >= 500) {
                    redisTemplate.delete(cacheKey);
                    log.info("Released idempotency lock {} due to 5xx response", idempotencyKey);
                }
            }
        } else {
            filterChain.doFilter(request, response);
        }
    }
}
