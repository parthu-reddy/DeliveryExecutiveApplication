package com.fooddelivery.order.service;

import com.fooddelivery.order.entity.LedgerAccount;
import com.fooddelivery.order.entity.LedgerEntry;
import com.fooddelivery.order.repository.ILedgerAccountRepository;
import com.fooddelivery.order.repository.ILedgerEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DoubleEntryLedgerServiceTest {

    @Mock
    private ILedgerAccountRepository accountRepository;

    @Mock
    private ILedgerEntryRepository entryRepository;

    @InjectMocks
    private DoubleEntryLedgerService ledgerService;

    private UUID transactionId;
    private UUID sourceOwnerId;
    private UUID targetOwnerId;

    @BeforeEach
    void setUp() {
        transactionId = UUID.randomUUID();
        sourceOwnerId = UUID.randomUUID();
        targetOwnerId = UUID.randomUUID();
    }

    @Test
    void recordTransaction_ShouldFail_WhenAmountIsZeroOrNegative() {
        assertThrows(IllegalArgumentException.class, () ->
            ledgerService.recordTransaction(transactionId, sourceOwnerId, "CUSTOMER", targetOwnerId, "RESTAURANT", BigDecimal.ZERO)
        );

        assertThrows(IllegalArgumentException.class, () ->
            ledgerService.recordTransaction(transactionId, sourceOwnerId, "CUSTOMER", targetOwnerId, "RESTAURANT", new BigDecimal("-10.00"))
        );
    }

    @Test
    void recordTransaction_ShouldSkip_WhenTransactionAlreadyExists() {
        when(entryRepository.existsByTransactionId(transactionId)).thenReturn(true);

        ledgerService.recordTransaction(transactionId, sourceOwnerId, "CUSTOMER", targetOwnerId, "RESTAURANT", new BigDecimal("10.00"));

        verify(accountRepository, never()).save(any());
        verify(entryRepository, never()).save(any(LedgerEntry.class));
    }

    @Test
    void recordTransaction_ShouldCreateAccountsAndEntries_WhenValid() {
        when(entryRepository.existsByTransactionId(transactionId)).thenReturn(false);

        LedgerAccount sourceAccount = LedgerAccount.builder().id(UUID.randomUUID()).ownerId(sourceOwnerId).ownerType("CUSTOMER").balance(new BigDecimal("100.00")).build();
        LedgerAccount targetAccount = LedgerAccount.builder().id(UUID.randomUUID()).ownerId(targetOwnerId).ownerType("RESTAURANT").balance(new BigDecimal("50.00")).build();

        when(accountRepository.findByOwnerIdAndOwnerType(sourceOwnerId, "CUSTOMER")).thenReturn(Optional.of(sourceAccount));
        when(accountRepository.findByOwnerIdAndOwnerType(targetOwnerId, "RESTAURANT")).thenReturn(Optional.of(targetAccount));

        ledgerService.recordTransaction(transactionId, sourceOwnerId, "CUSTOMER", targetOwnerId, "RESTAURANT", new BigDecimal("25.00"));

        // Verify account balances updated
        assertThat(sourceAccount.getBalance()).isEqualTo(new BigDecimal("75.00"));
        assertThat(targetAccount.getBalance()).isEqualTo(new BigDecimal("75.00"));

        verify(accountRepository, times(2)).save(any(LedgerAccount.class));

        // Verify Ledger Entries
        ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(entryRepository, times(2)).save(entryCaptor.capture());

        var entries = entryCaptor.getAllValues();
        assertThat(entries).hasSize(2);

        LedgerEntry debitEntry = entries.get(0);
        assertThat(debitEntry.getDirection()).isEqualTo("DEBIT");
        assertThat(debitEntry.getAmount()).isEqualTo(new BigDecimal("25.00"));
        assertThat(debitEntry.getAccountId()).isEqualTo(sourceAccount.getId());

        LedgerEntry creditEntry = entries.get(1);
        assertThat(creditEntry.getDirection()).isEqualTo("CREDIT");
        assertThat(creditEntry.getAmount()).isEqualTo(new BigDecimal("25.00"));
        assertThat(creditEntry.getAccountId()).isEqualTo(targetAccount.getId());
    }
}
