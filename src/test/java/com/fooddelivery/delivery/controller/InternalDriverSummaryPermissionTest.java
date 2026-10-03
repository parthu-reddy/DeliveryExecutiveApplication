package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.exception.GlobalExceptionHandler;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.DeliveryExecutiveProfileService;
import com.fooddelivery.delivery.service.OrderAssignmentService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual HTTP handlers and method advice: summary access cannot grant admin fleet access. */
@SpringJUnitConfig(InternalDriverSummaryPermissionTest.Config.class)
class InternalDriverSummaryPermissionTest {
    @Configuration @EnableMethodSecurity static class Config {
        @Bean IDeliveryExecutiveRepository profiles() { return mock(IDeliveryExecutiveRepository.class); }
        @Bean InternalDriverController summaries(IDeliveryExecutiveRepository r) { return new InternalDriverController(r); }
        @Bean AdminDeliveryController admin(IDeliveryExecutiveRepository r) {
            return new AdminDeliveryController(r, mock(DeliveryExecutiveProfileService.class), mock(OrderAssignmentService.class));
        }
    }
    @Autowired InternalDriverController summaries;
    @Autowired AdminDeliveryController admin;
    @Autowired IDeliveryExecutiveRepository profiles;
    private MockMvc http;
    private final UUID driverId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @BeforeEach void setup() {
        reset(profiles);
        var driver = new DeliveryExecutive();
        driver.setId(driverId); driver.setFullName("Test Driver");
        driver.setPhoneNumber("7000000001"); driver.setEmail("private@example.invalid");
        when(profiles.findById(driverId)).thenReturn(Optional.of(driver));
        when(profiles.findAllById(List.of(driverId))).thenReturn(List.of(driver));
        http = MockMvcBuilders.standaloneSetup(summaries, admin).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "restaurant-service", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
    @Test void serviceReadsMinimalSingleAndBatchSummaries() throws Exception {
        as("SERVICE");
        http.perform(get("/api/v1/internal/drivers/" + driverId)).andExpect(status().isOk())
                .andExpect(content().json("{\"id\":\"" + driverId + "\",\"fullName\":\"Test Driver\"}", true));
        http.perform(post("/api/v1/internal/drivers/summaries").contentType("application/json")
                .content("[\"" + driverId + "\"]")).andExpect(status().isOk())
                .andExpect(content().json("[{\"id\":\"" + driverId + "\",\"fullName\":\"Test Driver\"}]", true));
    }
    @Test void serviceDoesNotGainAdminDriverProfilesOrFleetData() throws Exception {
        as("SERVICE");
        http.perform(get("/api/v1/internal/admin/delivery/drivers/" + driverId)).andExpect(status().isForbidden());
        http.perform(post("/api/v1/internal/admin/delivery/drivers/batch").contentType("application/json")
                .content("[\"" + driverId + "\"]")).andExpect(status().isForbidden());
        http.perform(get("/api/v1/internal/admin/delivery/fleet-cities")).andExpect(status().isForbidden());
        verifyNoInteractions(profiles);
    }
    @Test void restaurantUserCannotCallInternalSummariesAsAService() throws Exception {
        as("RESTAURANT");
        http.perform(get("/api/v1/internal/drivers/" + driverId)).andExpect(status().isForbidden());
        http.perform(post("/api/v1/internal/drivers/summaries").contentType("application/json")
                .content("[\"" + driverId + "\"]")).andExpect(status().isForbidden());
        verifyNoInteractions(profiles);
    }
}
