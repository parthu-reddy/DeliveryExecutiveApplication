package com.fooddelivery.delivery.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class FeignClientInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            
            // Forward trusted internal headers instead of JWT, as microservices trust these directly
            String userId = request.getHeader("X-User-Id");
            if (userId != null) template.header("X-User-Id", userId);
            
            String userRoles = request.getHeader("X-User-Roles");
            if (userRoles != null) template.header("X-User-Roles", userRoles);
            
            String userPhone = request.getHeader("X-User-Phone");
            if (userPhone != null) template.header("X-User-Phone", userPhone);
            
            // Still forward Authorization header just in case some legacy downstream needs it
            String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authHeader != null) {
                template.header(HttpHeaders.AUTHORIZATION, authHeader);
            }
        } else {
            // Background Job / Async Execution (e.g. OnboardingOrchestratorService via scheduler/kafka)
            // Inject internal service token/headers so GovernmentIDValidationService accepts it
            template.header("X-User-Id", "system-internal");
            template.header("X-User-Roles", "INTERNAL_SERVICE,DELIVERY,ADMIN");
        }
    }
}
