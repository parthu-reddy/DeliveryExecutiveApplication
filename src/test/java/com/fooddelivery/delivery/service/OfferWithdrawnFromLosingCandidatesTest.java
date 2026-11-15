package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.delivery.client.OrderServiceClient;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.repository.OrderAssignmentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-2 (KE-7 on Dev, 2026-11-14): an offer goes to every candidate and the first to accept wins. The others' apps
 * kept showing "New Dispatch" until their next poll, and an Accept in that window answered 409. The winner's accept
 * must withdraw the offer from every other candidate, and only from them.
 */
class OfferWithdrawnFromLosingCandidatesTest {

    private static final UUID ORDER = UUID.fromString("9e37e6a5-5da4-4ebc-907e-7ef509ad6876");
    private static final String WINNER = "11111111-1111-1111-1111-111111111111";
    private static final String LOSER = "22222222-2222-2222-2222-222222222222";

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void theWinnersAcceptWithdrawsTheOfferFromEveryOtherCandidateOnly() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(List.of("SUCCESS", "1794662900000", WINNER, LOSER));
        DispatchOfferWithdrawal withdrawal = mock(DispatchOfferWithdrawal.class);
        LogisticsDispatchService dispatch = mock(LogisticsDispatchService.class);
        OrderAssignmentService service = new OrderAssignmentService(
                redis,
                mock(TransactionTemplate.class),
                mock(OutboxEventRepository.class),
                mock(OutboxEventHelper.class),
                mock(IDeliveryExecutiveRepository.class),
                dispatch,
                mock(OrderAssignmentRepository.class),
                new ObjectMapper(),
                mock(OrderServiceClient.class),
                java.time.Clock.fixed(java.time.Instant.parse("2026-11-14T12:00:00Z"), java.time.ZoneOffset.UTC),
                withdrawal);

        service.acceptOrderPing(UUID.fromString(WINNER), ORDER);

        verify(withdrawal).withdraw(LOSER, ORDER);
        verify(withdrawal, never()).withdraw(eq(WINNER), any());
        verify(dispatch).releaseDriverLock(LOSER);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void aRefusedAcceptWithdrawsNothing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(List.of("ALREADY_ACCEPTED"));
        DispatchOfferWithdrawal withdrawal = mock(DispatchOfferWithdrawal.class);
        OrderAssignmentService service = new OrderAssignmentService(
                redis, mock(TransactionTemplate.class), mock(OutboxEventRepository.class),
                mock(OutboxEventHelper.class), mock(IDeliveryExecutiveRepository.class),
                mock(LogisticsDispatchService.class), mock(OrderAssignmentRepository.class), new ObjectMapper(),
                mock(OrderServiceClient.class),
                java.time.Clock.fixed(java.time.Instant.parse("2026-11-14T12:00:00Z"), java.time.ZoneOffset.UTC),
                withdrawal);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.acceptOrderPing(UUID.fromString(LOSER), ORDER));

        verify(withdrawal, never()).withdraw(anyString(), any());
    }

    /** The rider app matches on exactly this shape (useDeliveryOrders socket handler). */
    @Test
    void theWithdrawalGoesOnTheRidersOwnChannelAsTypedJson() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        DispatchOfferWithdrawal withdrawal = new DispatchOfferWithdrawal(redis, new ObjectMapper());

        withdrawal.withdraw(LOSER, ORDER);

        org.mockito.ArgumentCaptor<String> message = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq("ws:driver:" + LOSER), message.capture());
        com.fasterxml.jackson.databind.JsonNode json = new ObjectMapper().readTree(message.getValue());
        assertThat(json.path("type").asText()).isEqualTo("DISPATCH_OFFER_WITHDRAWN");
        assertThat(json.path("orderId").asText()).isEqualTo(ORDER.toString());
    }
}
