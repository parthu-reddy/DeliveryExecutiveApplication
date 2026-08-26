package com.fooddelivery.delivery.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.fooddelivery.common.security.IdentityTokenService;
import java.time.Instant;

@Component
public class FeignClientInterceptor implements RequestInterceptor {

    private final IdentityTokenService identityTokenService;

    public FeignClientInterceptor(IdentityTokenService identityTokenService) {
        this.identityTokenService = identityTokenService;
    }

    @Override
    public void apply(RequestTemplate template) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            
            // Forward trusted internal headers instead of JWT, as microservices trust these directly
            String[] headersToForward = {
                com.fooddelivery.common.constants.HeaderConstants.HEADER_USER_ID, com.fooddelivery.common.constants.HeaderConstants.HEADER_USER_ROLES, com.fooddelivery.common.constants.HeaderConstants.HEADER_USER_PHONE,
                "X-Identity-Signature", "X-Issued-At", "X-Session-Id",
                "X-Calling-Service", "X-Device-Id"
            };
            
            for (String headerName : headersToForward) {
                String headerValue = request.getHeader(headerName);
                if (headerValue != null) {
                    template.header(headerName, headerValue);
                }
            }
            
            // Still forward Authorization header just in case some legacy downstream needs it
            String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authHeader != null) {
                template.header(HttpHeaders.AUTHORIZATION, authHeader);
            }
        } else {
            // Background Job / Async Execution (e.g. OnboardingOrchestratorService via scheduler/kafka)
            // Inject internal service token/headers so GovernmentIDValidationService accepts it
            String userId = "system-internal";
            String roles = "INTERNAL_SERVICE,DELIVERY,ADMIN,RESTAURANT,CUSTOMER";
            String phone = "";
            String sessionId = "system-internal-session";
            long issuedAt = Instant.now().getEpochSecond();
            String signature = identityTokenService.sign(userId, roles, phone, sessionId, issuedAt);

            template.header(com.fooddelivery.common.constants.HeaderConstants.HEADER_USER_ID, userId);
            template.header(com.fooddelivery.common.constants.HeaderConstants.HEADER_USER_ROLES, roles);
            template.header(com.fooddelivery.common.constants.HeaderConstants.HEADER_USER_PHONE, phone);
            template.header("X-Session-Id", sessionId);
            template.header("X-Issued-At", String.valueOf(issuedAt));
            template.header("X-Identity-Signature", signature);
        }
    }
}
