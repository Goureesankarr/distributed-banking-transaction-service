package com.bank.txn.service;

import com.bank.txn.domain.AppUser;
import com.bank.txn.domain.Transfer;
import com.bank.txn.error.ConcurrencyConflictException;
import com.bank.txn.error.InsufficientFundsException;
import com.bank.txn.error.ValidationException;
import com.bank.txn.repository.TransferRepository;
import com.bank.txn.web.dto.TransferRequest;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock private TransferExecutor executor;
    @Mock private TransferRepository transfers;
    @Mock private AccountService accountService;
    @Mock private IdempotencyService idempotency;
    @Mock private CachedAccountReader cache;
    @Mock private AuditService audit;

    private TransferService service;

    private static final String USER = "alice";
    private static final String KEY = "key-1";

    private static final TransferRequest REQUEST = new TransferRequest(
            "ACC1", "ACC2", new BigDecimal("25.00"), "USD", "rent");

    @BeforeEach
    void setUp() {
        service = new TransferService(
                executor, transfers, accountService, idempotency, cache, audit,
                JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                new SimpleMeterRegistry());
    }

    private static Transfer completedTransfer() {
        Transfer transfer = new Transfer("TRF-1", UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("25.00"), "USD", "rent", KEY, USER);
        transfer.markCompleted();
        return transfer;
    }

    private void givenKeyIsClaimable() {
        when(idempotency.begin(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(accountService.requireUser(USER))
                .thenReturn(new AppUser(USER, "hash", "Alice", "ROLE_USER"));
    }

    @Test
    @DisplayName("a contended attempt is retried against fresh state and still succeeds")
    void retriesOptimisticLockFailures() {
        givenKeyIsClaimable();
        when(executor.execute(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException("account", UUID.randomUUID()))
                .thenThrow(new ObjectOptimisticLockingFailureException("account", UUID.randomUUID()))
                .thenReturn(completedTransfer());

        TransferService.TransferOutcome outcome = service.transfer(USER, KEY, false, REQUEST);

        assertThat(outcome.status()).isEqualTo(201);
        assertThat(outcome.replayed()).isFalse();
        verify(executor, times(3)).execute(any());
        verify(idempotency).complete(KEY, USER, 201, outcome.body());
    }

    @Test
    @DisplayName("sustained contention gives up with 409 and frees the key for a retry")
    void givesUpAfterMaxAttempts() {
        givenKeyIsClaimable();
        when(executor.execute(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException("account", UUID.randomUUID()));

        assertThatThrownBy(() -> service.transfer(USER, KEY, false, REQUEST))
                .isInstanceOf(ConcurrencyConflictException.class);

        verify(executor, times(5)).execute(any());
        verify(idempotency).release(KEY, USER);
        verify(idempotency, never()).complete(anyString(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("a business rejection is bound to the key so retries see the same 422")
    void businessRejectionIsStoredAgainstTheKey() {
        givenKeyIsClaimable();
        when(executor.execute(any()))
                .thenThrow(new InsufficientFundsException("ACC1", BigDecimal.TEN, new BigDecimal("25.00")));
        when(executor.recordFailure(any(), anyString())).thenReturn(Optional.empty());

        TransferService.TransferOutcome outcome = service.transfer(USER, KEY, false, REQUEST);

        assertThat(outcome.status()).isEqualTo(422);
        assertThat(outcome.body()).contains("INSUFFICIENT_FUNDS");
        verify(executor).recordFailure(any(), anyString());
        verify(idempotency).complete(KEY, USER, 422, outcome.body());
    }

    @Test
    @DisplayName("a known key replays the stored response without touching the executor")
    void replaysStoredResponse() {
        when(idempotency.begin(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new IdempotencyService.StoredResponse(201, "{\"reference\":\"TRF-1\"}")));

        TransferService.TransferOutcome outcome = service.transfer(USER, KEY, false, REQUEST);

        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.status()).isEqualTo(201);
        assertThat(outcome.body()).isEqualTo("{\"reference\":\"TRF-1\"}");
        verify(executor, never()).execute(any());
    }

    @Test
    void requiresAnIdempotencyKey() {
        assertThatThrownBy(() -> service.transfer(USER, "  ", false, REQUEST))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Idempotency-Key");

        verify(idempotency, never()).begin(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void rejectsSelfTransfer() {
        TransferRequest selfTransfer = new TransferRequest(
                "ACC1", "ACC1", new BigDecimal("5.00"), "USD", null);

        assertThatThrownBy(() -> service.transfer(USER, KEY, false, selfTransfer))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must differ");
    }
}
