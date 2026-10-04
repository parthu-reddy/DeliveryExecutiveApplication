package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.audit.AuditReader;
import com.fooddelivery.common.client.GovernmentIdServiceClient;
import com.fooddelivery.common.dto.governmentid.ApplicationDocumentDto;
import com.fooddelivery.common.enums.*;
import com.fooddelivery.common.exception.GlobalExceptionHandler;
import com.fooddelivery.common.service.*;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.DeliveryApplicationService;
import com.fooddelivery.delivery.service.duty.RiderDutyNotifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real self-derived service writes and controller authorization through HTTP JSON binding. */
@SpringJUnitConfig(DeliveryApplicationAuthorizationTest.Config.class)
class DeliveryApplicationAuthorizationTest {
    @TestConfiguration @EnableMethodSecurity static class Config {
        @Bean static org.springframework.core.convert.ConversionService conversionService(){return new org.springframework.boot.convert.ApplicationConversionService();}
        @Bean IDeliveryExecutiveRepository executives(){return mock(IDeliveryExecutiveRepository.class);}
        @Bean GovernmentIdServiceClient government(){return mock(GovernmentIdServiceClient.class);}
        @Bean ApplicationEvents events(){return mock(ApplicationEvents.class);}
        @Bean RateLimitingService limits(){return mock(RateLimitingService.class);}
        @Bean AuditReader audit(){return mock(AuditReader.class);}
        @Bean jakarta.persistence.EntityManagerFactory entityManagerFactory(){return mock(jakarta.persistence.EntityManagerFactory.class);}
        @Bean DeliveryApplicationService applications(IDeliveryExecutiveRepository executives,GovernmentIdServiceClient government,
                ApplicationEvents events,RateLimitingService limits,AuditReader audit){return new DeliveryApplicationService(executives,government,events,limits,audit,
                        new SimpleMeterRegistry(),mock(StringRedisTemplate.class),mock(RiderDutyNotifier.class));}
        @Bean DeliveryApplicationController applicant(DeliveryApplicationService applications){return new DeliveryApplicationController(applications);}
        @Bean AdminDeliveryApplicationController admin(DeliveryApplicationService applications){return new AdminDeliveryApplicationController(applications);}
        @Bean InternalDeliveryApplicationController internal(DeliveryApplicationService applications){return new InternalDeliveryApplicationController(applications);}
    }
    @Autowired DeliveryApplicationController applicant;@Autowired AdminDeliveryApplicationController admin;
    @Autowired InternalDeliveryApplicationController internal;@Autowired IDeliveryExecutiveRepository executives;
    @Autowired GovernmentIdServiceClient government;@Autowired ApplicationEvents events;@Autowired RateLimitingService limits;
    UUID caller;MockMvc http;final String own="/api/v1/delivery-onboarding/application",adminBase="/api/v1/internal/admin/delivery-applications";
    @BeforeEach void setup(){
        reset(executives,government,events,limits);caller=UUID.randomUUID();auth("CUSTOMER");
        when(government.getDeliveryDocuments(caller)).thenReturn(List.of());
        when(government.getVerificationSummary(caller)).thenReturn(new GovernmentIdServiceClient.VerificationSummary(false,false,null,false,false,null));
        when(limits.resolveBucket(anyString(),anyInt(),anyInt(),any())).thenReturn(io.github.bucket4j.Bucket.builder().addLimit(io.github.bucket4j.Bandwidth.classic(30,io.github.bucket4j.Refill.intervally(30,Duration.ofHours(1)))).build());
        when(executives.saveAndFlush(any())).thenAnswer(inv -> {DeliveryExecutive row=inv.getArgument(0);row.setVersion(row.getVersion()==null?0:row.getVersion()+1);return row;});
        http=MockMvcBuilders.standaloneSetup(applicant,admin,internal).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void auth(String role){var value=new UsernamePasswordAuthenticationToken(caller.toString(),null,List.of(new SimpleGrantedAuthority("ROLE_"+role)));
        value.setDetails(Map.of("phone","8000000001"));SecurityContextHolder.getContext().setAuthentication(value);}
    @Test void applicantWithoutDeliveryRoleReadsOnlyTheirOwnApplication()throws Exception{
        http.perform(get(own).param("executiveId",UUID.randomUUID().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.data.executiveId").value(caller.toString()));
        verify(executives).findById(caller);verifyNoInteractions(events);
        http.perform(get(own+"/"+UUID.randomUUID())).andExpect(status().isNotFound());
    }
    @Test void draftIgnoresCallerChosenIdentityAndPhoneAndUsesTheSignedPrincipal()throws Exception{
        UUID forged=UUID.randomUUID();
        http.perform(put(own).contentType("application/json").content("{\"fullName\":\"Synthetic applicant\",\"vehicleNumber\":\"KA01AB1234\",\"vehicleType\":\"MCWG\",\"cityId\":\"BLR\",\"executiveId\":\""+forged+"\",\"phoneNumber\":\"9000000001\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.executiveId").value(caller.toString()));
        verify(executives).saveAndFlush(argThat(row -> row.getId().equals(caller) && row.getPhoneNumber().equals("8000000001") && row.getStatus()==DeliveryExecutiveStatus.OFFLINE && !row.isActive()));
        verify(executives,never()).findById(forged);
    }
    @ParameterizedTest @ValueSource(strings={"CUSTOMER","RESTAURANT","DELIVERY","SERVICE"})
    void adminApplicationEndpointsAreAdminOnly(String role)throws Exception{
        auth(role);
        for(String path:List.of(adminBase,adminBase+"/"+caller,adminBase+"/"+caller+"/history"))http.perform(get(path)).andExpect(status().isForbidden());
        for(String action:List.of("approve","reject","suspend","reinstate"))http.perform(post(adminBase+"/"+caller+"/"+action).contentType("application/json")
                .content("{\"version\":0,\"reason\":\"Current documents need review\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(executives,government,events);
    }
    @Test void anonymousAndServiceCredentialsCannotCreateAnApplicantsProfile()throws Exception{
        SecurityContextHolder.clearContext();http.perform(get(own)).andExpect(status().isUnauthorized());
        auth("SERVICE");http.perform(put(own).contentType("application/json").content("{\"fullName\":\"Synthetic applicant\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(executives,government,events);
    }
    @Test void internalDocumentContextRemainsServiceOnly()throws Exception{
        http.perform(get("/api/v1/internal/delivery-applications/"+caller+"/context")).andExpect(status().isForbidden());
        verifyNoInteractions(executives);auth("SERVICE");
        http.perform(get("/api/v1/internal/delivery-applications/"+caller+"/context")).andExpect(status().isOk()).andExpect(jsonPath("$.executiveId").value(caller.toString()));
    }
    @Test void approvingAdminComesFromThePrincipal()throws Exception{
        auth("ADMIN");var rider=new DeliveryExecutive();rider.setId(caller);rider.setVersion(0);rider.setFullName("Synthetic applicant");
        rider.setPhoneNumber("7000000001");rider.setVehicleType(VehicleClass.MCWG);rider.setApplicationStatus(ApplicationStatus.IN_REVIEW);
        when(executives.findById(caller)).thenReturn(Optional.of(rider));
        when(government.getVerificationSummary(caller)).thenReturn(new GovernmentIdServiceClient.VerificationSummary(true,true,"MCWG",true,true,Instant.now()));
        when(government.getDeliveryDocuments(caller)).thenReturn(List.of("DRIVING_LICENSE","RC","SELFIE").stream().map(type ->
                new ApplicationDocumentDto(UUID.randomUUID(),type,"documents/"+caller+"/accepted/file.jpg","image/jpeg",100,Instant.now())).toList());
        http.perform(post(adminBase+"/"+caller+"/approve").contentType("application/json").content("{\"version\":0,\"reviewedBy\":\""+UUID.randomUUID()+"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewedBy").value(caller.toString()));
    }
}
