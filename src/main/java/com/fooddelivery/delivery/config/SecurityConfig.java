package com.fooddelivery.delivery.config;

import com.fooddelivery.common.security.CommonSecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(CommonSecurityConfig.class)
public class SecurityConfig {
}
