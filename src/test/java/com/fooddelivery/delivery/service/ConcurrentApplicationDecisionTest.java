package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fooddelivery.common.audit.*;
import com.fooddelivery.common.client.GovernmentIdServiceClient;
import com.fooddelivery.common.constants.*;
import com.fooddelivery.common.enums.*;
import com.fooddelivery.common.outbox.entity.OutboxEventEntity;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.common.service.*;
import com.fooddelivery.delivery.dto.DeliveryApplicationDecisionRequest;
import com.fooddelivery.delivery.entity.*;
import com.fooddelivery.delivery.enums.*;
import com.fooddelivery.delivery.repository.*;
import com.fooddelivery.delivery.service.duty.RiderDutyNotifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fooddelivery.common.dto.governmentid.VerificationSummary;

/** Real embedded H2 transactions; no external database or application HTTP server. */
@DataJpaTest(properties = {"spring.cloud.config.enabled=false", "spring.flyway.enabled=false",
        // The contract-test listener is on the classpath but this H2 slice makes no HTTP calls.
        // Keep it from scanning a dynamic HTTPS port before the persistence context starts.
        "wiremock.server.https-port=-1",
        "spring.datasource.url=jdbc:h2:mem:o3_rider_transactions;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=com.fooddelivery.contract.CustomH2Dialect"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ConcurrentApplicationDecisionTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConcurrentApplicationDecisionTest {
    private static final AtomicInteger PHONES = new AtomicInteger(1);

    @TestConfiguration @EnableAutoConfiguration @EnableMethodSecurity
    @org.springframework.boot.autoconfigure.ImportAutoConfiguration({AuditConfiguration.class,
            com.fooddelivery.common.outbox.config.OutboxConfiguration.class})
    @EntityScan(basePackageClasses={DeliveryExecutive.class,AuditEvent.class,OutboxEventEntity.class})
    @EnableJpaRepositories(basePackageClasses=IDeliveryExecutiveRepository.class)
    static class Config {
        @Bean ObjectMapper mapper() {return new ObjectMapper().registerModule(new JavaTimeModule());}
        @Bean SimpleMeterRegistry metrics() {return new SimpleMeterRegistry();}
        @Bean KafkaTemplate<String,String> kafka() {return mock(KafkaTemplate.class);}
        @Bean DeliveryApplicationService applications(IDeliveryExecutiveRepository profiles,GovernmentIdServiceClient government,
                ApplicationEvents events,RateLimitingService limits,AuditReader audit,SimpleMeterRegistry metrics,
                StringRedisTemplate redis,RiderDutyNotifier duty) {
            return new DeliveryApplicationService(profiles,government,events,limits,audit,metrics,redis,duty);
        }
    }
    @MockBean GovernmentIdServiceClient government;
    @MockBean RateLimitingService limits;
    @MockBean StringRedisTemplate redis;
    @MockBean RiderDutyNotifier duty;
    @Autowired IDeliveryExecutiveRepository profiles;
    @Autowired OrderAssignmentRepository assignments;
    @Autowired DeliveryApplicationService applications;
    @Autowired OutboxEventRepository outbox;
    @Autowired AuditReader audit;
    @Autowired SimpleMeterRegistry metrics;
    @Autowired PlatformTransactionManager transactions;
    UUID id;
    SetOperations<String,String> available;
    @BeforeEach void setup() {
        var driver=new DeliveryExecutive();id=UUID.randomUUID();driver.setId(id);
        driver.setPhoneNumber("799900"+String.format("%04d",PHONES.getAndIncrement()));
        driver.setFullName("Synthetic transaction applicant");driver.setCityId("BLR");driver.setVehicleNumber("TEST-"+id);
        driver.setVehicleType(VehicleClass.MCWG);driver.setStatus(DeliveryExecutiveStatus.OFFLINE);
        driver.setApplicationStatus(ApplicationStatus.IN_REVIEW);profiles.saveAndFlush(driver);
        when(government.getVerificationSummary(id)).thenReturn(new VerificationSummary(true,true,"MCWG",true,true,null));
        when(government.getDeliveryDocuments(id)).thenReturn(List.of("DRIVING_LICENSE","RC","SELFIE").stream().map(type ->
                new com.fooddelivery.common.dto.governmentid.ApplicationDocumentDto(UUID.randomUUID(),type,
                        "documents/"+id+"/accepted/"+UUID.randomUUID()+".jpg","image/jpeg",100,java.time.Instant.now())).toList());
        available=mock(SetOperations.class);when(redis.opsForSet()).thenReturn(available);
    }
    @AfterEach void clear() {SecurityContextHolder.clearContext();}
    private static void admin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(),null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    private double transitionCount() {return metrics.find("applications.transitions").counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();}
    private List<OutboxEventEntity> applicationRows() {return outbox.findAll().stream().filter(row -> row.getAggregateId().equals(id.toString())).toList();}

    @Test void concurrentAdminsCommitOneDecisionAndTheLoserReadsCurrentState() throws Exception {
        double before=transitionCount();var bothRead=new CyclicBarrier(2);
        var barrier=new org.springframework.aop.support.DefaultPointcutAdvisor(new org.springframework.aop.support.StaticMethodMatcherPointcut() {
            @Override public boolean matches(java.lang.reflect.Method method,Class<?> type) {return method.getName().equals("findById");}
        },(org.aopalliance.intercept.MethodInterceptor) invocation -> {
            Object read=invocation.proceed();if(Thread.currentThread().getName().startsWith("O3-rider-admin-")) bothRead.await(10,TimeUnit.SECONDS);return read;
        });
        var proxy=(org.springframework.aop.framework.Advised)profiles;proxy.addAdvisor(0,barrier);
        var names=new AtomicInteger();var workers=Executors.newFixedThreadPool(2,task -> new Thread(task,"O3-rider-admin-"+names.incrementAndGet()));
        record Outcome(boolean success,ApplicationStatus target) { }
        var outcomes=new ArrayList<Outcome>();
        try {
            var pending=new ArrayList<Future<Outcome>>();
            for(var target:List.of(ApplicationStatus.APPROVED,ApplicationStatus.REJECTED)) pending.add(workers.submit(() -> {
                admin();try {
                    var request=new DeliveryApplicationDecisionRequest(0,"Please provide a legible document.");
                    if(target==ApplicationStatus.APPROVED) applications.approve(id,request);else applications.reject(id,request);
                    return new Outcome(true,target);
                } catch(ApplicationConflictException expected) {return new Outcome(false,target);}
                finally {SecurityContextHolder.clearContext();}
            }));
            for(var future:pending) outcomes.add(future.get(20,TimeUnit.SECONDS));
        } finally {workers.shutdownNow();proxy.removeAdvisor(barrier);}
        assertThat(outcomes.stream().filter(Outcome::success).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(result -> !result.success()).count()).isEqualTo(1);
        var winner=outcomes.stream().filter(Outcome::success).findFirst().orElseThrow().target();
        var current=applications.currentForConflict(id);assertThat(current.get("status")).isEqualTo(winner);assertThat(current.get("version")).isEqualTo(1);
        var history=audit.history("DELIVERY_APPLICATION",id,0,25).getContent();assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction()).isEqualTo(winner==ApplicationStatus.APPROVED?AuditAction.APPLICATION_APPROVED:AuditAction.APPLICATION_REJECTED);
        var rows=applicationRows();assertThat(rows).hasSize(2);
        assertThat(rows.stream().filter(row -> row.getEventType()==EventType.DELIVERY_APPLICATION_STATUS_CHANGED))
                .singleElement().extracting(OutboxEventEntity::getIdempotencyKey).isEqualTo("delivery-application:"+id+":1");
        assertThat(rows.stream().filter(row -> row.getEventType()!=EventType.DELIVERY_APPLICATION_STATUS_CHANGED)).hasSize(1);
        assertThat(transitionCount()).isEqualTo(before+1);
    }
    @Test void outerRollbackRestoresReviewAndLeavesNoAuditOutboxOrMetric() {
        admin();double before=transitionCount();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {applications.approve(id,new DeliveryApplicationDecisionRequest(0,null));tx.setRollbackOnly();});
        assertThat(profiles.findById(id).orElseThrow().getApplicationStatus()).isEqualTo(ApplicationStatus.IN_REVIEW);
        assertThat(profiles.findById(id).orElseThrow().getVersion()).isZero();
        assertThat(audit.history("DELIVERY_APPLICATION",id,0,25).getContent()).isEmpty();assertThat(applicationRows()).isEmpty();
        assertThat(transitionCount()).isEqualTo(before);verifyNoInteractions(duty);verify(redis,never()).opsForHash();verify(available,never()).remove(any(),any());
    }
    @Test void persistedVehicleRegistrationsMustBeUnique() {
        var first=profiles.findById(id).orElseThrow();
        var duplicate=new DeliveryExecutive();duplicate.setId(UUID.randomUUID());duplicate.setPhoneNumber("799900"+String.format("%04d",PHONES.getAndIncrement()));
        duplicate.setFullName("Synthetic duplicate vehicle");duplicate.setVehicleNumber(first.getVehicleNumber());duplicate.setStatus(DeliveryExecutiveStatus.OFFLINE);
        assertThatThrownBy(() -> profiles.saveAndFlush(duplicate)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    private OrderAssignment activeDelivery() {
        var driver=profiles.findById(id).orElseThrow();driver.setApplicationStatus(ApplicationStatus.APPROVED);driver.setActive(true);
        driver.setStatus(DeliveryExecutiveStatus.ON_DELIVERY);driver.setLastBiometricVerificationAt(Instant.now());profiles.saveAndFlush(driver);
        var saved=assignments.saveAndFlush(OrderAssignment.builder().orderId(UUID.randomUUID()).driverId(id).state(OrderAssignment.State.ASSIGNED)
                .pickupOtp("123456").deliveryOtp("654321").paymentMethod(PaymentMethod.UPI).deliveryStatus(DeliveryStatus.OUT_FOR_DELIVERY).assignedAt(Instant.now()).build());
        // Compare persisted state before and after the decision, including the database timestamp precision.
        return assignments.findById(saved.getOrderId()).orElseThrow();
    }
    private void assignmentUnchanged(OrderAssignment before) {
        var after=assignments.findById(before.getOrderId()).orElseThrow();
        assertThat(after).usingRecursiveComparison().isEqualTo(before);
    }
    @Test void suspensionPreservesActiveAssignmentAndDeliveryLivenessWhileRefusingFutureDuty() {
        var assignment=activeDelivery();admin();int version=profiles.findById(id).orElseThrow().getVersion();
        applications.suspend(id,new DeliveryApplicationDecisionRequest(version,"Documents require another review."));
        var driver=profiles.findById(id).orElseThrow();assertThat(driver.getApplicationStatus()).isEqualTo(ApplicationStatus.SUSPENDED);
        assertThat(driver.isActive()).isFalse();assertThat(driver.getStatus()).isEqualTo(DeliveryExecutiveStatus.ON_DELIVERY);assignmentUnchanged(assignment);
        verify(available).remove(RedisKeyConstants.PREFIX_DRIVERS_AVAILABLE+"BLR",id.toString());
        verify(redis,never()).opsForZSet();verify(redis,never()).opsForHash();verify(duty).publish(id,DeliveryExecutiveStatus.ON_DELIVERY,DutyChangeReason.SUSPENDED);
        assertThatThrownBy(() -> RiderReadiness.requireEligibleForDuty(driver,true,Instant.now())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SUSPENDED");
    }
    @Test void rollbackOfSuspensionPreservesAssignmentAvailabilityAndNotificationState() {
        var assignment=activeDelivery();admin();int version=profiles.findById(id).orElseThrow().getVersion();double before=transitionCount();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            applications.suspend(id,new DeliveryApplicationDecisionRequest(version,"Documents require another review."));tx.setRollbackOnly();
        });
        var driver=profiles.findById(id).orElseThrow();assertThat(driver.getApplicationStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(driver.isActive()).isTrue();assertThat(driver.getStatus()).isEqualTo(DeliveryExecutiveStatus.ON_DELIVERY);assertThat(driver.getVersion()).isEqualTo(version);
        assignmentUnchanged(assignment);assertThat(audit.history("DELIVERY_APPLICATION",id,0,25).getContent()).isEmpty();assertThat(applicationRows()).isEmpty();
        assertThat(transitionCount()).isEqualTo(before);verifyNoInteractions(duty);verify(available,never()).remove(any(),any());verify(redis,never()).opsForZSet();verify(redis,never()).opsForHash();
    }
}
