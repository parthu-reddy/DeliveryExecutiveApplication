package com.fooddelivery.delivery.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Set;
import org.slf4j.MDC;
import com.fooddelivery.common.constants.HeaderConstants;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@lombok.extern.slf4j.Slf4j
public class LoggingFilter extends OncePerRequestFilter {
    @java.lang.SuppressWarnings("all")

    /**
     * Headers that must NEVER be logged to prevent credential leakage.
     */
    private static final Set<String> SENSITIVE_HEADERS = Set.of("authorization", "cookie", "x-api-key", "x-forwarded-for");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        long startTime = System.currentTimeMillis();
        if (log.isDebugEnabled()) {
            log.debug("REQUEST {} {}", request.getMethod(), request.getRequestURI());
            Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                if (!SENSITIVE_HEADERS.contains(headerName.toLowerCase())) {
                    log.debug("HEADER {}: {}", headerName, request.getHeader(headerName));
                }
            }
        }
        try {
            if (request.getHeader(HeaderConstants.HEADER_USER_ID) != null) {
                MDC.put("userId", request.getHeader(HeaderConstants.HEADER_USER_ID));
            }
            if (request.getHeader(HeaderConstants.HEADER_SESSION_ID) != null) {
                MDC.put("sessionId", request.getHeader(HeaderConstants.HEADER_SESSION_ID));
            }
            if (request.getHeader(HeaderConstants.HEADER_CALLING_SERVICE) != null) {
                MDC.put("callingService", request.getHeader(HeaderConstants.HEADER_CALLING_SERVICE));
            }
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
            long duration = System.currentTimeMillis() - startTime;
            log.debug("RESPONSE {} {} - {} ({}ms)", request.getMethod(), request.getRequestURI(), response.getStatus(), duration);
        }
    }
}
