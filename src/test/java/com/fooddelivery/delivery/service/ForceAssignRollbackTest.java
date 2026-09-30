package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.enums.VerificationStatus;
import com.fooddelivery.common.outbox.entity.OutboxEventEntity;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.delivery.client.CustomerServiceClient;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.entity.OrderAssignment;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.repository.OrderAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the manual path: a failed validation must not erase candidate pings or
 * silence redispatch, while a durable assignment updates every projection only after commit.
 */
class ForceAssignRollbackTest {

    private static final UUID ORDER = UUID.fromString("083610e8-d508-4b12-a78b-7c4e13cf3ade");
    private static final UUID DRIVER = UUID.fromString("4f4a4e37-6ca5-5598-94f1-43ef1628f631");
    private static final UUID ACTOR = UUID.fromString("4f4a4e37-6ca5-5598-94f1-43ef1628f632");
    private static final String CITY = "BLR";
    private static final String OPERATION = "admin-manual:assign:operation-0001";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private IDeliveryExecutiveRepository repository;
    private OrderAssignmentRepository assignments;
    private OutboxEventRepository outbox;
    private CustomerServiceClient customer;
    private OrderAssignmentService service;

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        GeoOperations<String, String> geo = mock(GeoOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(mock(SetOperations.class));
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForGeo()).thenReturn(geo);
        when(redis.opsForHash()).thenReturn(mock(HashOperations.class));
        when(zset.score(RiderLiveness.LAST_PING_KEY, DRIVER.toString()))
                .thenReturn((double) System.currentTimeMillis());
        when(geo.position("drivers:geo:" + CITY, DRIVER.toString()))
                .thenReturn(List.of(new Point(77.5946, 12.9716)));

        repository = mock(IDeliveryExecutiveRepository.class);
        assignments = mock(OrderAssignmentRepository.class);
        outbox = mock(OutboxEventRepository.class);
        customer = mock(CustomerServiceClient.class);
        when(assignments.findLockedByOrderId(ORDER)).thenReturn(Optional.empty());
        when(outbox.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(customer.getOrderDispatchDetails(ORDER)).thenReturn(dispatchContext());

        TransactionTemplate transaction = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> body = invocation.getArgument(0);
            return body.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        }).when(transaction).execute(any());

        OutboxEventHelper helper = mock(OutboxEventHelper.class);
        doAnswer(invocation -> OutboxEventEntity.builder()
                .aggregateType((com.fooddelivery.common.constants.AggregateType) invocation.getArgument(0))
                .aggregateId((String) invocation.getArgument(1))
                .eventType((com.fooddelivery.common.constants.EventType) invocation.getArgument(2))
                .payload(objectMapper.writeValueAsString(invocation.getArgument(3)))
                .build())
                .when(helper).createOutboxEvent(any(), anyString(), any(), any());

        service = new OrderAssignmentService(
                redis,
                transaction,
                outbox,
                helper,
                repository,
                mock(LogisticsDispatchService.class),
                assignments,
                objectMapper,
                customer);
    }

    @Test
    void offlineDriverProducesDurableFailureBeforeAnyDispatchStateIsDestroyed() {
        DeliveryExecutive offline = readyDriver(DRIVER);
        offline.setStatus(DeliveryExecutiveStatus.OFFLINE);
        when(repository.findLockedById(DRIVER)).thenReturn(Optional.of(offline));

        ManualForceAssignmentResult result = service.forceAssignOrder(command());

        assertThat(result.rejected()).isTrue();
        assertThat(result.failureCode()).isEqualTo("DRIVER_NOT_ONLINE");
        verify(redis, never()).delete(eq(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_PING_PENDING + ORDER));
        verify(values, never()).set(
                eq(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_LOCK + ORDER),
                anyString(), any(Duration.class));
        verify(values, never()).set(
                eq(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER + DRIVER),
                anyString(), any(Duration.class));
        verify(outbox).save(org.mockito.ArgumentMatchers.argThat(event ->
                event.getIdempotencyKey().equals("delivery-manual-assignment-failure:" + OPERATION)
                        && event.getEventType() == com.fooddelivery.common.constants.EventType.MANUAL_ASSIGNMENT_FAILED));
    }

    @Test
    void successfulManualAssignmentIsAuditedAndProjectedAfterCommit() {
        DeliveryExecutive executive = readyDriver(DRIVER);
        when(repository.findLockedById(DRIVER)).thenReturn(Optional.of(executive));

        ManualForceAssignmentResult result = service.forceAssignOrder(command());

        assertThat(executive.getStatus()).isEqualTo(DeliveryExecutiveStatus.ON_DELIVERY);
        assertThat(result.applied()).isTrue();
        assertThat(executive.getStatus()).isEqualTo(DeliveryExecutiveStatus.ON_DELIVERY);
        verify(outbox).save(org.mockito.ArgumentMatchers.argThat(event ->
                event.getIdempotencyKey().equals("delivery-force-assignment:" + OPERATION)
                        && event.getEventType() == com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED));
        verify(values).set(eq(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + ORDER),
                eq(DRIVER.toString()), any(Duration.class));
        verify(values).set(eq(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_DRIVER_ACTIVE_ORDER + DRIVER),
                eq(ORDER.toString()), any(Duration.class));
        verify(values).set(eq(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DISPATCH_LOCK + ORDER),
                eq("CANCELLED"), any(Duration.class));
    }

    @Test
    void manualAssignmentDoesNotProjectRedisUntilTheEnclosingConsumerTransactionCommits() {
        DeliveryExecutive executive = readyDriver(DRIVER);
        when(repository.findLockedById(DRIVER)).thenReturn(Optional.of(executive));
        TransactionSynchronizationManager.initSynchronization();

        ManualForceAssignmentResult result = service.forceAssignOrder(command());

        assertThat(result.applied()).isTrue();
        verify(values, never()).set(
                eq(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + ORDER),
                anyString(), any(Duration.class));
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

        TransactionSynchronizationUtils.triggerAfterCommit();

        verify(values).set(eq(com.fooddelivery.common.constants.RedisKeyConstants.PREFIX_ORDER_DRIVER_LOCK + ORDER),
                eq(DRIVER.toString()), any(Duration.class));
    }

    @Test
    void matchingOperationReplayDoesNotMutateRiderOrRedisAgain() throws Exception {
        OutboxEventEntity existing = OutboxEventEntity.builder()
                .eventType(com.fooddelivery.common.constants.EventType.DRIVER_ASSIGNED)
                .payload(objectMapper.writeValueAsString(com.fooddelivery.common.event.DriverAssignedEvent.builder()
                        .orderId(ORDER.toString())
                        .driverId(DRIVER.toString())
                        .operationId(OPERATION)
                        .actorId(ACTOR.toString())
                        .reason("Rider confirmed nearby")
                        .build()))
                .build();
        when(outbox.findByIdempotencyKey("delivery-force-assignment:" + OPERATION))
                .thenReturn(Optional.of(existing));

        ManualForceAssignmentResult result = service.forceAssignOrder(command());

        assertThat(result.replayed()).isTrue();
        verify(repository, never()).findLockedById(any());
        verify(assignments, never()).save(any());
        verify(redis, never()).delete(anyString());
    }

    @Test
    void reassignmentReleasesOnlyTheFormerRiderAndResetsDeliveryProgress() {
        UUID formerDriverId = UUID.fromString("4f4a4e37-6ca5-5598-94f1-43ef1628f633");
        DeliveryExecutive target = readyDriver(DRIVER);
        DeliveryExecutive former = readyDriver(formerDriverId);
        former.setStatus(DeliveryExecutiveStatus.ON_DELIVERY);
        OrderAssignment assignment = OrderAssignment.builder()
                .orderId(ORDER)
                .driverId(formerDriverId)
                .state(OrderAssignment.State.ASSIGNED)
                .assignedAt(Instant.now())
                .build();
        when(assignments.findLockedByOrderId(ORDER)).thenReturn(Optional.of(assignment));
        when(repository.findLockedById(DRIVER)).thenReturn(Optional.of(target));
        when(repository.findLockedById(formerDriverId)).thenReturn(Optional.of(former));

        ManualForceAssignmentResult result = service.forceAssignOrder(command());

        assertThat(former.getStatus()).isEqualTo(DeliveryExecutiveStatus.ONLINE);
        assertThat(target.getStatus()).isEqualTo(DeliveryExecutiveStatus.ON_DELIVERY);
        assertThat(assignment.getDriverId()).isEqualTo(DRIVER);
        assertThat(assignment.getDeliveryStatus()).isNull();
        assertThat(result.applied()).isTrue();
        verify(repository).save(former);
    }

    @Test
    void matchingFailureReplayDoesNotRetryTheSameManualOperation() throws Exception {
        OutboxEventEntity existing = OutboxEventEntity.builder()
                .eventType(com.fooddelivery.common.constants.EventType.MANUAL_ASSIGNMENT_FAILED)
                .payload(objectMapper.writeValueAsString(com.fooddelivery.common.event.ManualAssignmentFailedEvent.builder()
                        .orderId(ORDER.toString())
                        .driverId(DRIVER.toString())
                        .operationId(OPERATION)
                        .actorId(ACTOR.toString())
                        .reasonCode("DRIVER_NOT_ONLINE")
                        .build()))
                .build();
        when(outbox.findByIdempotencyKey("delivery-manual-assignment-failure:" + OPERATION))
                .thenReturn(Optional.of(existing));

        ManualForceAssignmentResult result = service.forceAssignOrder(command());

        assertThat(result.rejected()).isTrue();
        assertThat(result.failureCode()).isEqualTo("DRIVER_NOT_ONLINE");
        verify(repository, never()).findLockedById(any());
        verify(assignments, never()).save(any());
        verify(outbox, never()).save(any());
    }

    private ManualForceAssignment command() {
        return new ManualForceAssignment(ORDER, DRIVER, OPERATION, ACTOR,
                "Rider confirmed nearby", CITY);
    }

    private Map<String, String> dispatchContext() {
        return Map.of(
                "pickupOtp", "123456",
                "deliveryOtp", "654321",
                "paymentMethod", "UPI",
                "deliveryStatus", "MANUAL_INTERVENTION_REQUIRED",
                "manualInterventionOperationId", OPERATION,
                "manualInterventionRequestedDriverId", DRIVER.toString(),
                "dispatchCityId", CITY);
    }

    private DeliveryExecutive readyDriver(UUID id) {
        DeliveryExecutive executive = new DeliveryExecutive();
        executive.setId(id);
        executive.setCityId(CITY);
        executive.setVehicleNumber("KA01AB1234");
        executive.setVerificationStatus(VerificationStatus.APPROVED);
        executive.setActive(true);
        executive.setLastBiometricVerificationAt(Instant.now());
        executive.setStatus(DeliveryExecutiveStatus.ONLINE);
        return executive;
    }
}
