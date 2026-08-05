package com.bank.txn.web;

import com.bank.txn.domain.Transfer;
import com.bank.txn.error.AccessDeniedForAccountException;
import com.bank.txn.error.ResourceNotFoundException;
import com.bank.txn.repository.LedgerEntryRepository;
import com.bank.txn.service.AccountService;
import com.bank.txn.service.TransferService;
import com.bank.txn.web.dto.LedgerEntryView;
import com.bank.txn.web.dto.TransferRequest;
import com.bank.txn.web.dto.TransferView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers")
public class TransferController {

    private final TransferService transfers;
    private final AccountService accounts;
    private final LedgerEntryRepository ledger;

    public TransferController(TransferService transfers,
                              AccountService accounts,
                              LedgerEntryRepository ledger) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.ledger = ledger;
    }

    /**
     * The response body is produced as raw JSON rather than a typed object so
     * that a replayed request returns the <em>exact</em> bytes of the original
     * response, which is what an idempotency key promises the client.
     */
    @PostMapping
    @Operation(summary = "Move money between two accounts",
            description = """
                    Requires an `Idempotency-Key` header. Retrying with the same key returns the
                    original response and never moves money a second time; reusing a key with a
                    different body is rejected with 422.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transfer completed",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TransferView.class))),
            @ApiResponse(responseCode = "409", description = "Same key already in flight, or lock contention"),
            @ApiResponse(responseCode = "422", description = "Rejected: funds, currency, account state or key reuse"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    public ResponseEntity<String> create(
            Authentication auth,
            @Parameter(description = "Client-generated unique key, e.g. a UUID", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        TransferService.TransferOutcome outcome = transfers.transfer(
                CurrentUser.username(auth), idempotencyKey, CurrentUser.isAdmin(auth), request);

        return ResponseEntity.status(outcome.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotent-Replay", String.valueOf(outcome.replayed()))
                .body(outcome.body());
    }

    @GetMapping("/{reference}")
    @Operation(summary = "Fetch a transfer by its reference")
    public TransferView get(Authentication auth, @PathVariable String reference) {
        return TransferView.from(requireVisibleTransfer(auth, reference));
    }

    @GetMapping("/{reference}/ledger")
    @Operation(summary = "The double-entry legs posted for a transfer")
    public List<LedgerEntryView> ledgerEntries(Authentication auth, @PathVariable String reference) {
        Transfer transfer = requireVisibleTransfer(auth, reference);
        return ledger.findByTransferId(transfer.getId()).stream()
                .map(LedgerEntryView::from)
                .toList();
    }

    private Transfer requireVisibleTransfer(Authentication auth, String reference) {
        Transfer transfer = transfers.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", reference));
        if (CurrentUser.isAdmin(auth)) {
            return transfer;
        }
        var ownerId = accounts.requireUser(CurrentUser.username(auth)).getId();
        boolean partyToIt =
                accounts.requireAccountById(transfer.getSourceAccountId()).getOwnerId().equals(ownerId)
                        || accounts.requireAccountById(transfer.getTargetAccountId()).getOwnerId().equals(ownerId);
        if (!partyToIt) {
            throw new AccessDeniedForAccountException(reference);
        }
        return transfer;
    }
}
