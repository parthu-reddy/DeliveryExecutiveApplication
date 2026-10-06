package com.fooddelivery.delivery.service;

import com.fooddelivery.common.audit.AuditReader;
import com.fooddelivery.common.client.GovernmentIdServiceClient;
import com.fooddelivery.common.dto.governmentid.ApplicationDocumentDto;
import com.fooddelivery.common.enums.*;
import com.fooddelivery.common.service.*;
import com.fooddelivery.delivery.dto.*;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.*;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.duty.RiderDutyNotifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.core.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fooddelivery.common.dto.governmentid.VerificationSummary;

class DeliveryApplicationServiceTest {
    final IDeliveryExecutiveRepository repository=mock(IDeliveryExecutiveRepository.class);
    final GovernmentIdServiceClient government=mock(GovernmentIdServiceClient.class);
    final ApplicationEvents events=mock(ApplicationEvents.class);
    final RateLimitingService limits=mock(RateLimitingService.class);
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    final RiderDutyNotifier duty=mock(RiderDutyNotifier.class);
    final DeliveryApplicationService service=new DeliveryApplicationService(repository,government,events,limits,mock(AuditReader.class),new SimpleMeterRegistry(),redis,duty);
    final DeliveryExecutive rider=new DeliveryExecutive();
    final VerificationSummary approved=new VerificationSummary(true,true,"MCWG",true,true,null);
    @BeforeEach void setUp() {
        rider.setId(UUID.randomUUID());rider.setFullName("Synthetic applicant");rider.setPhoneNumber("7999887766");
        rider.setVehicleNumber("KA01AB1234");rider.setVehicleType(VehicleClass.MCWG);rider.setCityId("BLR");
        rider.setVersion(4);rider.setStatus(DeliveryExecutiveStatus.OFFLINE);
        auth(rider.getId(),"CUSTOMER");
        when(repository.findById(rider.getId())).thenReturn(Optional.of(rider));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> {DeliveryExecutive value=inv.getArgument(0);value.setVersion(value.getVersion()==null?0:value.getVersion()+1);return value;});
        when(government.getVerificationSummary(rider.getId())).thenReturn(approved);
        when(government.getDeliveryDocuments(rider.getId())).thenReturn(documents());
        var bucket=io.github.bucket4j.Bucket.builder().addLimit(io.github.bucket4j.Bandwidth.classic(30,io.github.bucket4j.Refill.intervally(30,java.time.Duration.ofHours(1)))).build();
        when(limits.resolveBucket(anyString(),anyInt(),anyInt(),any())).thenReturn(bucket);
        TransactionSynchronizationManager.initSynchronization();
    }
    @AfterEach void clearContext() {TransactionSynchronizationManager.clearSynchronization();SecurityContextHolder.clearContext();}
    void auth(UUID user,String role) {var token=new UsernamePasswordAuthenticationToken(user.toString(),null,List.of(new SimpleGrantedAuthority("ROLE_"+role)));token.setDetails(Map.of("phone","7999887766"));SecurityContextHolder.getContext().setAuthentication(token);}
    List<ApplicationDocumentDto> documents() {return List.of("DRIVING_LICENSE","RC","SELFIE").stream().map(type -> new ApplicationDocumentDto(UUID.randomUUID(),type,"documents/"+rider.getId()+"/accepted/"+type+".jpg","image/jpeg",100,Instant.now())).toList();}
    @Test void draftUsesPrincipalAndSignedPhoneRatherThanCallerSelectedIdentity() {
        when(repository.findById(rider.getId())).thenReturn(Optional.empty());
        var result=service.put(new DeliveryApplicationDraftRequest("Synthetic applicant","KA01AB1234",VehicleClass.MCWG,"BLR",null,null));
        assertThat(result.executiveId()).isEqualTo(rider.getId());assertThat(result.phoneNumber()).isEqualTo("7999887766");
        assertThat(result.status()).isEqualTo(ApplicationStatus.DRAFT);
        verify(events).delivery(eq(rider.getId()),eq(ApplicationStatus.DRAFT),isNull(),eq(0L),any(),eq(rider.getId()),eq("USER"),eq(AuditAction.APPLICATION_DRAFT_CREATED),eq(List.of(rider.getId())));
    }
    @Test void missingSignedPhoneCannotCreateProfile() {
        when(repository.findById(rider.getId())).thenReturn(Optional.empty());((UsernamePasswordAuthenticationToken)SecurityContextHolder.getContext().getAuthentication()).setDetails(null);
        assertThatThrownBy(() -> service.put(new DeliveryApplicationDraftRequest("Synthetic applicant",null,null,null,null,null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("401");verify(repository,never()).saveAndFlush(any());
    }
    @Test void submittingStartsChecksAndPassedChecksReachReviewWithoutActivating() {
        var result=service.submit(4);assertThat(result.status()).isEqualTo(ApplicationStatus.SUBMITTED);
        assertThat(rider.isActive()).isFalse();assertThat(rider.getVerificationApplicationVersion()).isEqualTo(5L);
        service.processChecks(rider.getId(),5);
        assertThat(rider.getApplicationStatus()).isEqualTo(ApplicationStatus.IN_REVIEW);assertThat(rider.isActive()).isFalse();
        verify(events).delivery(eq(rider.getId()),eq(ApplicationStatus.IN_REVIEW),isNull(),eq(6L),any(),isNull(),eq("SERVICE"),eq(AuditAction.APPLICATION_IN_REVIEW),eq(List.of(rider.getId())));
    }
    @Test void failedChecksRejectWithAReasonAndStaleSubmissionDoesNoWork() {
        rider.setApplicationStatus(ApplicationStatus.SUBMITTED);rider.setVerificationApplicationVersion(4L);
        service.processChecks(rider.getId(),3);verify(repository,never()).saveAndFlush(any());
        when(government.getVerificationSummary(rider.getId())).thenReturn(new VerificationSummary(false,false,null,false,false,null));
        service.processChecks(rider.getId(),4);assertThat(rider.getApplicationStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(rider.getRejectionReason()).hasSizeBetween(10,500);assertThat(rider.isActive()).isFalse();
    }
    @Test void missingCompletedDocumentsCannotSubmit() {
        when(government.getDeliveryDocuments(rider.getId())).thenReturn(List.of());
        assertThatThrownBy(() -> service.submit(4)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Upload and complete");verifyNoInteractions(events);
    }
    @ParameterizedTest @EnumSource(value=ApplicationStatus.class,names={"SUBMITTED","IN_REVIEW","APPROVED","SUSPENDED"})
    void noneditableApplicationsCannotBeRewritten(ApplicationStatus status) {
        rider.setApplicationStatus(status);
        assertThatThrownBy(() -> service.put(new DeliveryApplicationDraftRequest("Changed",null,null,null,null,4))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(repository,never()).saveAndFlush(any());
    }
    @Test void rejectedEditPreservesRejectionUntilResubmit() {
        rider.setApplicationStatus(ApplicationStatus.REJECTED);rider.setRejectionReason("Documents require another review.");
        service.put(new DeliveryApplicationDraftRequest("Corrected applicant","KA01AB1234",VehicleClass.MCWG,"BLR",null,4));
        assertThat(rider.getApplicationStatus()).isEqualTo(ApplicationStatus.REJECTED);assertThat(rider.getRejectionReason()).isNotBlank();
        service.submit(5);assertThat(rider.getApplicationStatus()).isEqualTo(ApplicationStatus.SUBMITTED);assertThat(rider.getRejectionReason()).isNull();
    }
    @Test void onlyAdminDecisionAfterReviewCanApproveAndActivate() {
        UUID admin=UUID.randomUUID();auth(admin,"ADMIN");rider.setApplicationStatus(ApplicationStatus.IN_REVIEW);
        service.approve(rider.getId(),new DeliveryApplicationDecisionRequest(4,null));
        assertThat(rider.getApplicationStatus()).isEqualTo(ApplicationStatus.APPROVED);assertThat(rider.isActive()).isTrue();
        assertThat(rider.getReviewedBy()).isEqualTo(admin);
        verify(events).delivery(eq(rider.getId()),eq(ApplicationStatus.APPROVED),isNull(),eq(5L),any(),eq(admin),eq("ADMIN"),eq(AuditAction.APPLICATION_APPROVED),eq(List.of(rider.getId())));
    }
    @ParameterizedTest @EnumSource(value=ApplicationStatus.class,names={"IN_REVIEW","SUSPENDED"})
    void approvalAndReinstatementRequireCompletedPrivateDocumentsBeforeAnyDecision(ApplicationStatus status) {
        auth(UUID.randomUUID(),"ADMIN");rider.setApplicationStatus(status);rider.setActive(false);
        for (List<ApplicationDocumentDto> absent : List.of(List.<ApplicationDocumentDto>of(),
                List.of(new ApplicationDocumentDto(UUID.randomUUID(),"SELFIE","documents/"+rider.getId()+"/accepted/file.jpg","image/jpeg",100,null)))) {
            when(government.getDeliveryDocuments(rider.getId())).thenReturn(absent);
            assertThatThrownBy(() -> {
                if(status==ApplicationStatus.IN_REVIEW)service.approve(rider.getId(),new DeliveryApplicationDecisionRequest(4,null));
                else service.reinstate(rider.getId(),new DeliveryApplicationDecisionRequest(4,null));
            }).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
            assertThat(rider.getApplicationStatus()).isEqualTo(status);assertThat(rider.isActive()).isFalse();
            assertThat(rider.getReviewedBy()).isNull();verify(repository,never()).saveAndFlush(any());verifyNoInteractions(events);
        }
        when(government.getDeliveryDocuments(rider.getId())).thenReturn(null);
        assertThatThrownBy(() -> {
            if(status==ApplicationStatus.IN_REVIEW)service.approve(rider.getId(),new DeliveryApplicationDecisionRequest(4,null));
            else service.reinstate(rider.getId(),new DeliveryApplicationDecisionRequest(4,null));
        }).isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
        verifyNoInteractions(events);verify(repository,never()).saveAndFlush(any());
    }
    @Test void submittedOrChangedDecisionCannotBeApproved() {
        auth(UUID.randomUUID(),"ADMIN");rider.setApplicationStatus(ApplicationStatus.SUBMITTED);
        assertThatThrownBy(() -> service.approve(rider.getId(),new DeliveryApplicationDecisionRequest(4,null))).isInstanceOf(ApplicationConflictException.class);
        rider.setApplicationStatus(ApplicationStatus.IN_REVIEW);
        assertThatThrownBy(() -> service.approve(rider.getId(),new DeliveryApplicationDecisionRequest(3,null))).isInstanceOf(ApplicationConflictException.class);
        verifyNoInteractions(events);
    }
    @Test void rejectNeedsReasonAndCannotBeUsedToReinstate() {
        auth(UUID.randomUUID(),"ADMIN");rider.setApplicationStatus(ApplicationStatus.IN_REVIEW);
        assertThatThrownBy(() -> service.reject(rider.getId(),new DeliveryApplicationDecisionRequest(4,"short"))).isInstanceOf(IllegalArgumentException.class);
        service.reject(rider.getId(),new DeliveryApplicationDecisionRequest(4,"Documents require another review."));
        assertThat(rider.getApplicationStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThatThrownBy(() -> service.reinstate(rider.getId(),new DeliveryApplicationDecisionRequest(5,null))).isInstanceOf(ApplicationConflictException.class);
    }
    @Test void onDeliverySuspensionPreservesDutyAndTrackingUntilDeliveryFinishes() {
        auth(UUID.randomUUID(),"ADMIN");rider.setApplicationStatus(ApplicationStatus.APPROVED);rider.setActive(true);rider.setStatus(DeliveryExecutiveStatus.ON_DELIVERY);
        service.suspend(rider.getId(),new DeliveryApplicationDecisionRequest(4,"Documents require another review."));
        assertThat(rider.getStatus()).isEqualTo(DeliveryExecutiveStatus.ON_DELIVERY);assertThat(rider.isActive()).isFalse();
        assertThat(rider.getApplicationStatus()).isEqualTo(ApplicationStatus.SUSPENDED);verifyNoInteractions(redis,duty);
        var sets=mock(SetOperations.class);when(redis.opsForSet()).thenReturn(sets);
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        verify(sets).remove(anyString(),eq(rider.getId().toString()));verify(redis,never()).opsForZSet();verify(redis,never()).opsForHash();
        verify(duty).publish(rider.getId(),DeliveryExecutiveStatus.ON_DELIVERY,DutyChangeReason.SUSPENDED);
        assertThatThrownBy(() -> RiderReadiness.requireEligibleForDuty(rider,false,Instant.now())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Admin approval");
    }
    @Test void approvedInactiveRefreshStaysInactiveAndMissingChecksSuspendThroughLifecycle() {
        rider.setApplicationStatus(ApplicationStatus.APPROVED);rider.setActive(false);service.observeChecks(rider.getId(),approved);assertThat(rider.isActive()).isFalse();
        rider.setActive(true);service.observeChecks(rider.getId(),new VerificationSummary(false,false,null,false,false,null));
        assertThat(rider.getApplicationStatus()).isEqualTo(ApplicationStatus.SUSPENDED);assertThat(rider.isActive()).isFalse();
    }
}
