package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.client.MapsServiceClient;
import com.fooddelivery.common.dto.maps.SetAvailabilityRequest;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.delivery.client.CustomerServiceClient;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.repository.OrderAssignmentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A decline that could not be recorded must undo its own release.
 *
 * <p>{@code rejectOrderPing} releases the driver back into the Maps pool before writing the
 * ORDER_DRIVER_REJECTED outbox event. When that write fails it restores the pending ping — but it
 * used to leave the release standing, so the driver was simultaneously advertised as free and
 * holding a ping for this order. Selection could then pick them for a different order and overwrite
 * {@code driver:pending_ping}, losing the restored ping entirely.
 *
 * <p>The re-reserve goes through {@code /api/fleet/availability} with {@code available=false}.
 * {@code /api/fleet/release} cannot do it: that endpoint ignores the flag and always releases.
 */
class RejectRollbackReReservesTest {

    private static final UUID ORDER = UUID.fromString("083610e8-d508-4b12-a78b-7c4e13cf3ade");
    private static final UUID DRIVER = UUID.fromString("4f4a4e37-6ca5-5598-94f1-43ef1628f631");
    private static final String CITY = "BLR";

    @SuppressWarnings({"rawtypes", "unchecked"})
    private OrderAssignmentService serviceThatFailsTheOutboxWrite(LogisticsDispatchService dispatch) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(redis.opsForSet()).thenReturn(mock(SetOperations.class));
        when(redis.opsForZSet()).thenReturn(mock(ZSetOperations.class));
        when(redis.opsForHash()).thenReturn(mock(HashOperations.class));
        // The last candidate declined, so the LAST_REJECT branch runs.
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn("LAST_REJECT");

        TransactionTemplate tx = mock(TransactionTemplate.class);
        doThrow(new IllegalStateException("outbox unavailable")).when(tx).executeWithoutResult(any());

        return new OrderAssignmentService(
                redis, tx,
                mock(OutboxEventRepository.class),
                mock(OutboxEventHelper.class),
                mock(IDeliveryExecutiveRepository.class),
                dispatch,
                mock(OrderAssignmentRepository.class),
                new ObjectMapper(),
                mock(CustomerServiceClient.class));
    }

    @Test
    void aDeclineThatCannotBeRecordedTakesTheDriverBackOutOfThePool() {
        LogisticsDispatchService dispatch = mock(LogisticsDispatchService.class);

        assertThatThrownBy(() -> serviceThatFailsTheOutboxWrite(dispatch).rejectOrderPing(DRIVER, ORDER))
                .isInstanceOf(IllegalStateException.class);

        verify(dispatch).releaseDriverLock(DRIVER.toString());
        verify(dispatch).reserveDriverLock(DRIVER.toString());
    }

    /**
     * The re-reserve is best-effort: it must not replace the outbox failure the caller needs to see.
     */
    @Test
    void aFailedReReserveDoesNotMaskTheRealError() {
        LogisticsDispatchService dispatch = mock(LogisticsDispatchService.class);
        doThrow(new RuntimeException("maps down")).when(dispatch).reserveDriverLock(any());

        assertThatThrownBy(() -> serviceThatFailsTheOutboxWrite(dispatch).rejectOrderPing(DRIVER, ORDER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox unavailable");
    }

    /** The direction is the whole point: available=false, and via the endpoint that honours it. */
    @Test
    void reserveSendsAvailableFalse() {
        MapsServiceClient maps = mock(MapsServiceClient.class);
        IDeliveryExecutiveRepository repo = mock(IDeliveryExecutiveRepository.class);
        DeliveryExecutive executive = new DeliveryExecutive();
        executive.setId(DRIVER);
        executive.setCityId(CITY);
        when(repo.findById(DRIVER)).thenReturn(Optional.of(executive));

        new LogisticsDispatchService(
                mock(org.springframework.kafka.core.KafkaTemplate.class),
                new ObjectMapper(), maps, repo)
                .reserveDriverLock(DRIVER.toString());

        ArgumentCaptor<SetAvailabilityRequest> captor = ArgumentCaptor.forClass(SetAvailabilityRequest.class);
        verify(maps).setDriverAvailability(captor.capture());
        assertThat(captor.getValue().getAvailable())
                .describedAs("re-reserving means available=false")
                .isFalse();
        assertThat(captor.getValue().getCityId()).isEqualTo(CITY);
        assertThat(captor.getValue().getDriverId()).isEqualTo(DRIVER.toString());
    }

    @Test
    void anUnknownCityIsNotReReservedRatherThanSentWithNulls() {
        MapsServiceClient maps = mock(MapsServiceClient.class);
        IDeliveryExecutiveRepository repo = mock(IDeliveryExecutiveRepository.class);
        when(repo.findById(DRIVER)).thenReturn(Optional.empty());

        new LogisticsDispatchService(
                mock(org.springframework.kafka.core.KafkaTemplate.class),
                new ObjectMapper(), maps, repo)
                .reserveDriverLock(DRIVER.toString());

        verify(maps, org.mockito.Mockito.never()).setDriverAvailability(any());
    }
}
