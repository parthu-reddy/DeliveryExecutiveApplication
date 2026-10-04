package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.enums.ApplicationStatus;
import com.fooddelivery.common.exception.GlobalExceptionHandler;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.OrderAssignmentRepository;
import com.fooddelivery.delivery.service.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises the real readiness refusal, controller catch and ordered advice as one HTTP boundary. */
class DailySelfieRequiredHttpTest {
    DeliveryExecutiveProfileService profile;
    DeliveryExecutive rider;
    MockMvc http;
    @BeforeEach void setup() {
        profile=mock(DeliveryExecutiveProfileService.class);
        rider=new DeliveryExecutive();rider.setId(UUID.randomUUID());rider.setCityId("BLR");
        rider.setVehicleNumber("KA01AB1234");rider.setApplicationStatus(ApplicationStatus.APPROVED);
        rider.setActive(true);rider.setStatus(DeliveryExecutiveStatus.OFFLINE);
        doAnswer(inv -> {RiderReadiness.requireEligibleForDuty(rider,true,Instant.now());return rider;})
                .when(profile).goOnline(eq(rider.getId()),anyDouble(),anyDouble());
        var controller=new DeliveryExecutiveController(profile,mock(OrderAssignmentService.class),mock(OrderExecutionService.class),
                mock(OrderAssignmentRepository.class),mock(StringRedisTemplate.class),mock(RedisMessageListenerContainer.class));
        http=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler(),new DailySelfieRequiredHandler()).build();
    }
    String body(UUID id) {return "{\"driverId\":\""+id+"\",\"available\":true,\"lat\":12.9842,\"lng\":77.6658}";}
    @Test void expiredSelfieReturnsThePromptCodeWithoutChangingDuty() throws Exception {
        http.perform(post("/api/delivery/status").principal(() -> rider.getId().toString()).contentType("application/json").content(body(rider.getId())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("DAILY_SELFIE_REQUIRED"));
        assertThat(rider.getStatus()).isEqualTo(DeliveryExecutiveStatus.OFFLINE);
        assertThat(rider.isActive()).isTrue();verify(profile,never()).goOffline(any(),any());
    }
    @Test void suspendedApplicationDoesNotMisreportASelfieRefusal() throws Exception {
        rider.setApplicationStatus(ApplicationStatus.SUSPENDED);rider.setActive(false);
        http.perform(post("/api/delivery/status").principal(() -> rider.getId().toString()).contentType("application/json").content(body(rider.getId())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Admin approval")))
                .andExpect(jsonPath("$.errorCode").doesNotExist());
        assertThat(rider.getStatus()).isEqualTo(DeliveryExecutiveStatus.OFFLINE);
    }
    @Test void anotherDriversBodyIsRejectedBeforeReadinessOrDutyWrites() throws Exception {
        http.perform(post("/api/delivery/status").principal(() -> rider.getId().toString()).contentType("application/json").content(body(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(profile);
    }
}
