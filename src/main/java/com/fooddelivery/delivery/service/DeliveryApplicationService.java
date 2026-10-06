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

import io.micrometer.core.instrument.*;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sole lifecycle owner: passed provider checks prepare admin review, never grant duty.
 *
 * <p><strong>@replication-safe: per-replica read-only gauge</strong> -- {@code refreshQueueAge}
 * reads the authoritative review queue and changes only this replica's {@code AtomicReference}.
 * Each replica must export its own current gauge; concurrent reads do not change an application.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryApplicationService {
    private final IDeliveryExecutiveRepository executives;
    private final GovernmentIdServiceClient government;
    private final ApplicationEvents events;
    private final RateLimitingService limits;
    private final AuditReader audit;
    private final MeterRegistry metrics;
    private final StringRedisTemplate redis;
    private final RiderDutyNotifier duty;

    @Value("${platform.fleet.allowed-city-ids:BLR}")
    private String fleetCities = "BLR";

    private final AtomicReference<Instant> oldestReview = new AtomicReference<>();

    @PostConstruct
    void registerGauge() {
        Gauge.builder(
                        "applications.in_review.oldest_age_seconds",
                        oldestReview,
                        value ->
                                value.get() == null
                                        ? 0
                                        : Math.max(
                                                0,
                                                Duration.between(value.get(), Instant.now())
                                                        .toSeconds()))
                .tag("type", "delivery")
                .register(metrics);
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void refreshQueueAge() {
        oldestReview.set(executives.oldestSubmission(ApplicationStatus.IN_REVIEW));
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public DeliveryApplicationDto mine() {
        UUID id = person(applicant());
        var executive = executives.findById(id);
        if (executive.isEmpty())
            return new DeliveryApplicationDto(
                    id,
                    ApplicationStatus.DRAFT,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true,
                    true,
                    null,
                    List.of());
        return details(executive.get());
    }

    @Transactional(readOnly = true)
    public boolean isApproved(UUID id) {
        var executive = executive(id);
        return executive.getApplicationStatus() == ApplicationStatus.APPROVED
                && executive.isActive();
    }

    @PreAuthorize("hasRole('SERVICE')")
    @Transactional(readOnly = true)
    public com.fooddelivery.common.dto.governmentid.DeliveryApplicationContextDto documentContext(
            UUID id) {
        var executive = executives.findById(id);
        return new com.fooddelivery.common.dto.governmentid.DeliveryApplicationContextDto(
                id,
                executive
                        .map(DeliveryExecutive::getApplicationStatus)
                        .orElse(ApplicationStatus.DRAFT),
                executive.map(DeliveryExecutive::getVersion).orElse(null));
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public com.fooddelivery.common.dto.governmentid.DocumentPurpose selfDocumentPurpose(
            String type) {
        var executive = executives.findById(person(applicant()));
        return "SELFIE".equals(type)
                        && executive
                                .map(
                                        d ->
                                                d.getApplicationStatus()
                                                                == ApplicationStatus.APPROVED
                                                        || d.getApplicationStatus()
                                                                == ApplicationStatus.SUSPENDED)
                                .orElse(false)
                ? com.fooddelivery.common.dto.governmentid.DocumentPurpose.DELIVERY_DUTY
                : com.fooddelivery.common.dto.governmentid.DocumentPurpose.DELIVERY;
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional
    public DeliveryApplicationDto put(DeliveryApplicationDraftRequest request) {
        Authentication auth = applicant();
        UUID id = person(auth);
        new ApplicationRateLimits(limits).deliveryWrite(id);
        DeliveryExecutive executive = executives.findById(id).orElse(null);
        boolean created = executive == null;
        if (created) {
            executive = new DeliveryExecutive();
            executive.setId(id);
            executive.setStatus(DeliveryExecutiveStatus.OFFLINE);
            Object phone =
                    auth.getDetails() instanceof Map<?, ?> details ? details.get("phone") : null;
            if (!(phone instanceof String number) || !number.matches("[0-9]{10}"))
                throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Sign in again to provide your verified phone number");
            executive.setPhoneNumber(number);
        } else {
            editable(executive);
            requireVersion(executive, request.version());
        }
        executive.setFullName(required(request.fullName(), 100, "Full name"));
        executive.setVehicleNumber(optional(request.vehicleNumber(), 50));
        executive.setVehicleType(request.vehicleType());
        executive.setCityId(
                request.cityId() == null || request.cityId().isBlank()
                        ? null
                        : com.fooddelivery.common.location.FleetCityScope.resolve(
                                request.cityId(), fleetCities));
        executive.setPhotoUrl(optional(request.photoUrl(), 512));
        try {
            save(executive);
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A delivery profile already exists for these details");
        }
        if (created) emit(executive, id, "USER", AuditAction.APPLICATION_DRAFT_CREATED);
        return details(executive);
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional
    public DeliveryApplicationDto submit(Integer version) {
        UUID id = person(applicant());
        new ApplicationRateLimits(limits).deliveryWrite(id);
        var executive = executive(id);
        editable(executive);
        requireVersion(executive, version);
        required(executive.getFullName(), 100, "Full name");
        required(executive.getVehicleNumber(), 50, "Vehicle registration");
        if (executive.getVehicleType() == null)
            throw new IllegalArgumentException("Choose your vehicle type before submitting");
        executive.setCityId(
                com.fooddelivery.common.location.FleetCityScope.resolve(
                        executive.getCityId(), fleetCities));
        var documents = government.getDeliveryDocuments(id);
        if (documents == null
                || !Set.of("DRIVING_LICENSE", "RC", "SELFIE").stream()
                        .allMatch(
                                type ->
                                        documents.stream()
                                                .anyMatch(doc -> type.equals(doc.docType()))))
            throw new IllegalArgumentException(
                    "Upload and complete your driving license, vehicle registration and selfie"
                        + " before submitting");
        executive.setRejectionReason(null);
        executive.setReviewedAt(null);
        executive.setReviewedBy(null);
        executive.setSubmittedAt(Instant.now());
        executive.setVerificationApplicationVersion(
                Math.addExact(executive.getVersion().longValue(), 1));
        transition(
                executive,
                ApplicationStatus.SUBMITTED,
                id,
                "USER",
                AuditAction.APPLICATION_SUBMITTED);
        return details(executive);
    }

    @Transactional
    public void processChecks(UUID id, long submissionVersion) {
        var executive = executive(id);
        if (executive.getApplicationStatus() != ApplicationStatus.SUBMITTED
                || !Objects.equals(
                        executive.getVerificationApplicationVersion(), submissionVersion)) return;
        observe(executive, government.getVerificationSummary(id));
    }

    /**
     * Compatibility status refresh preserves existing inactive decisions and records fresh
     * biometric time.
     */
    @Transactional
    public void observeChecks(UUID id, GovernmentIdServiceClient.VerificationSummary summary) {
        observe(executive(id), summary);
    }

    private void observe(
            DeliveryExecutive executive, GovernmentIdServiceClient.VerificationSummary summary) {
        if (summary == null)
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Verification checks are unavailable. Please retry.");
        if (summary.lastBiometricVerificationAt() != null
                && (executive.getLastBiometricVerificationAt() == null
                        || summary.lastBiometricVerificationAt()
                                .isAfter(executive.getLastBiometricVerificationAt())))
            executive.setLastBiometricVerificationAt(summary.lastBiometricVerificationAt());
        boolean passed =
                summary.allDocsApproved()
                        && summary.bankApproved()
                        && OnboardingOrchestratorService.isVehicleClassCompatible(
                                executive.getVehicleType(), summary.dlVehicleClass());
        if (executive.getApplicationStatus() == ApplicationStatus.SUBMITTED) {
            if (!passed)
                executive.setRejectionReason(
                        "Document, vehicle-class or bank verification checks did not pass. Review"
                            + " the results and resubmit.");
            transition(
                    executive,
                    passed ? ApplicationStatus.IN_REVIEW : ApplicationStatus.REJECTED,
                    null,
                    "SERVICE",
                    passed ? AuditAction.APPLICATION_IN_REVIEW : AuditAction.APPLICATION_REJECTED);
        } else if (executive.getApplicationStatus() == ApplicationStatus.APPROVED && !passed) {
            executive.setRejectionReason(
                    "Required document or bank verification checks no longer pass. Contact support"
                        + " for review.");
            suspendState(executive);
            transition(
                    executive,
                    ApplicationStatus.SUSPENDED,
                    null,
                    "SERVICE",
                    AuditAction.APPLICATION_SUSPENDED);
        } else save(executive);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Slice<DeliveryApplicationQueueDto> queue(ApplicationStatus status, int page) {
        if (page < 0 || page > 100000) throw new IllegalArgumentException("Choose a valid page");
        return executives
                .findByApplicationStatus(
                        status, PageRequest.of(page, 25, Sort.by("submittedAt", "id")))
                .map(
                        d ->
                                new DeliveryApplicationQueueDto(
                                        d.getId(),
                                        d.getFullName(),
                                        d.getCityId(),
                                        d.getApplicationStatus(),
                                        d.getSubmittedAt(),
                                        d.getVersion()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public DeliveryApplicationDto adminDetail(UUID id) {
        return details(executive(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Slice<com.fooddelivery.common.audit.AuditEvent> history(UUID id, int page) {
        executive(id);
        return audit.history("DELIVERY_APPLICATION", id, page, 25);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public DeliveryApplicationDto approve(UUID id, DeliveryApplicationDecisionRequest request) {
        return decide(
                id,
                request,
                ApplicationStatus.IN_REVIEW,
                ApplicationStatus.APPROVED,
                AuditAction.APPLICATION_APPROVED);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public DeliveryApplicationDto reject(UUID id, DeliveryApplicationDecisionRequest request) {
        return decide(
                id,
                request,
                ApplicationStatus.IN_REVIEW,
                ApplicationStatus.REJECTED,
                AuditAction.APPLICATION_REJECTED);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public DeliveryApplicationDto suspend(UUID id, DeliveryApplicationDecisionRequest request) {
        return decide(
                id,
                request,
                ApplicationStatus.APPROVED,
                ApplicationStatus.SUSPENDED,
                AuditAction.APPLICATION_SUSPENDED);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public DeliveryApplicationDto reinstate(UUID id, DeliveryApplicationDecisionRequest request) {
        return decide(
                id,
                request,
                ApplicationStatus.SUSPENDED,
                ApplicationStatus.APPROVED,
                AuditAction.APPLICATION_APPROVED);
    }

    private DeliveryApplicationDto decide(
            UUID id,
            DeliveryApplicationDecisionRequest request,
            ApplicationStatus source,
            ApplicationStatus target,
            AuditAction action) {
        var executive = executive(id);
        UUID admin = person(SecurityContextHolder.getContext().getAuthentication());
        requireVersion(executive, request.version());
        if (executive.getApplicationStatus() != source) throw new ApplicationConflictException(id);
        if (target == ApplicationStatus.APPROVED) {
            var documents = government.getDeliveryDocuments(id);
            if (documents == null)
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Application documents are temporarily unavailable. Refresh before"
                            + " approving.");
            if (!Set.of("DRIVING_LICENSE", "RC", "SELFIE").stream()
                    .allMatch(
                            type ->
                                    documents.stream()
                                            .anyMatch(
                                                    doc ->
                                                            type.equals(doc.docType())
                                                                    && doc.uploadedAt() != null)))
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Completed private driving licence, registration and selfie documents are"
                            + " required before approval");
            var checks = government.getVerificationSummary(id);
            if (checks == null
                    || !checks.allDocsApproved()
                    || !checks.bankApproved()
                    || !OnboardingOrchestratorService.isVehicleClassCompatible(
                            executive.getVehicleType(), checks.dlVehicleClass()))
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Required verification checks must pass before approval");
            executive.setRejectionReason(null);
            executive.setActive(true);
        } else {
            executive.setRejectionReason(reason(request.reason()));
            if (target == ApplicationStatus.SUSPENDED) suspendState(executive);
        }
        executive.setReviewedAt(Instant.now());
        executive.setReviewedBy(admin);
        transition(executive, target, admin, "ADMIN", action);
        return details(executive);
    }

    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    @Transactional
    public void suspendForChecks(UUID id) {
        var executive = executive(id);
        if (executive.getApplicationStatus() == ApplicationStatus.SUSPENDED) return;
        if (executive.getApplicationStatus() != ApplicationStatus.APPROVED
                && executive.getApplicationStatus() != ApplicationStatus.SUBMITTED) return;
        executive.setRejectionReason(
                "Biometric verification failed repeatedly. Contact support for review.");
        boolean approved = executive.getApplicationStatus() == ApplicationStatus.APPROVED;
        if (approved) suspendState(executive);
        transition(
                executive,
                approved ? ApplicationStatus.SUSPENDED : ApplicationStatus.REJECTED,
                null,
                "SERVICE",
                approved ? AuditAction.APPLICATION_SUSPENDED : AuditAction.APPLICATION_REJECTED);
    }

    private void suspendState(DeliveryExecutive executive) {
        executive.setActive(false);
        if (executive.getStatus() == DeliveryExecutiveStatus.ONLINE)
            executive.setStatus(DeliveryExecutiveStatus.OFFLINE);
        UUID id = executive.getId();
        String city = executive.getCityId();
        DeliveryExecutiveStatus status = executive.getStatus();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            if (city != null)
                                redis.opsForSet()
                                        .remove(
                                                com.fooddelivery.common.constants.RedisKeyConstants
                                                                .PREFIX_DRIVERS_AVAILABLE
                                                        + city,
                                                id.toString());
                            if (status != DeliveryExecutiveStatus.ON_DELIVERY) {
                                redis.opsForZSet()
                                        .remove(RiderLiveness.LAST_PING_KEY, id.toString());
                                redis.opsForHash()
                                        .put("drivers:status", id.toString(), status.name());
                            }
                        } catch (RuntimeException unavailable) {
                            log.error(
                                    "SUSPENSION_PROJECTION_FAILED executiveId={} status={}",
                                    id,
                                    status);
                        }
                        duty.publish(id, status, DutyChangeReason.SUSPENDED);
                    }
                });
    }

    private void transition(
            DeliveryExecutive executive,
            ApplicationStatus target,
            UUID actor,
            String kind,
            AuditAction action) {
        ApplicationStatus.requireTransition(executive.getApplicationStatus(), target);
        executive.setApplicationStatus(target);
        save(executive);
        emit(executive, actor, kind, action);
    }

    private void emit(DeliveryExecutive executive, UUID actor, String kind, AuditAction action) {
        events.delivery(
                executive.getId(),
                executive.getApplicationStatus(),
                executive.getRejectionReason(),
                executive.getVersion(),
                Instant.now(),
                actor,
                kind,
                action,
                List.of(executive.getId()));
    }

    private void save(DeliveryExecutive executive) {
        try {
            executives.saveAndFlush(executive);
        } catch (org.springframework.dao.OptimisticLockingFailureException stale) {
            throw new ApplicationConflictException(executive.getId());
        }
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> currentForConflict(UUID id) {
        var executive = executive(id);
        return Map.of(
                "executiveId",
                id,
                "status",
                executive.getApplicationStatus(),
                "version",
                executive.getVersion());
    }

    private DeliveryApplicationDto details(DeliveryExecutive executive) {
        boolean available = true;
        List<ApplicationDocumentDto> documents = List.of();
        GovernmentIdServiceClient.VerificationSummary checks = null;
        try {
            documents = government.getDeliveryDocuments(executive.getId());
            checks = government.getVerificationSummary(executive.getId());
        } catch (ResponseStatusException unavailable) {
            if (!unavailable.getStatusCode().is5xxServerError()) throw unavailable;
            available = false;
        }
        if (documents == null) {
            documents = List.of();
            available = false;
        }
        return new DeliveryApplicationDto(
                executive.getId(),
                executive.getApplicationStatus(),
                executive.getVersion(),
                executive.getFullName(),
                executive.getPhoneNumber(),
                executive.getVehicleNumber(),
                executive.getVehicleType(),
                executive.getCityId(),
                executive.getPhotoUrl(),
                executive.getRejectionReason(),
                executive.getSubmittedAt(),
                executive.getReviewedAt(),
                executive.getReviewedBy(),
                editableState(executive),
                available,
                checks,
                documents);
    }

    private DeliveryExecutive executive(UUID id) {
        return executives
                .findById(id)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Application not found"));
    }

    private static boolean editableState(DeliveryExecutive d) {
        return d.getApplicationStatus() == ApplicationStatus.DRAFT
                || d.getApplicationStatus() == ApplicationStatus.REJECTED;
    }

    private static void editable(DeliveryExecutive d) {
        if (!editableState(d))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Only Draft and Rejected applications can be edited");
    }

    private static void requireVersion(DeliveryExecutive d, Integer version) {
        if (version == null || !version.equals(d.getVersion()))
            throw new ApplicationConflictException(d.getId());
    }

    private static String reason(String value) {
        if (value == null || value.trim().length() < 10 || value.trim().length() > 500)
            throw new IllegalArgumentException("Give a reason between 10 and 500 characters");
        return value.trim();
    }

    private static String required(String value, int max, String label) {
        var text = optional(value, max);
        if (text == null) throw new IllegalArgumentException(label + " is required");
        return text;
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > max)
            throw new IllegalArgumentException("Application field is too long");
        return value.trim();
    }

    private static Authentication applicant() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        person(auth);
        if (auth.getAuthorities().stream()
                .anyMatch(role -> role.getAuthority().equals("ROLE_SERVICE")))
            throw new AccessDeniedException("An applicant identity is required");
        return auth;
    }

    private static UUID person(Authentication auth) {
        if (auth == null || !auth.isAuthenticated())
            throw new AccessDeniedException("Authenticated applicant required");
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException invalid) {
            throw new AccessDeniedException("Authenticated applicant required");
        }
    }
}
