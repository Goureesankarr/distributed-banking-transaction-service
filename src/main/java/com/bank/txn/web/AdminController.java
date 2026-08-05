package com.bank.txn.web;

import com.bank.txn.domain.AccountStatus;
import com.bank.txn.domain.AuditLog;
import com.bank.txn.repository.AuditLogRepository;
import com.bank.txn.repository.LedgerEntryRepository;
import com.bank.txn.repository.OutboxEventRepository;
import com.bank.txn.service.AccountService;
import com.bank.txn.web.dto.AccountView;
import com.bank.txn.web.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Administration")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AccountService accounts;
    private final AuditLogRepository auditLogs;
    private final LedgerEntryRepository ledger;
    private final OutboxEventRepository outbox;

    public AdminController(AccountService accounts,
                           AuditLogRepository auditLogs,
                           LedgerEntryRepository ledger,
                           OutboxEventRepository outbox) {
        this.accounts = accounts;
        this.auditLogs = auditLogs;
        this.ledger = ledger;
        this.outbox = outbox;
    }

    @PatchMapping("/accounts/{accountNumber}/status")
    @Operation(summary = "Freeze, close or reactivate an account")
    public AccountView setStatus(Authentication auth,
                                 @PathVariable String accountNumber,
                                 @RequestParam AccountStatus status) {
        return accounts.setStatus(CurrentUser.username(auth), accountNumber, status);
    }

    @GetMapping("/audit")
    @Operation(summary = "Audit trail for one actor, newest first")
    public PageResponse<AuditLogView> audit(@RequestParam String actor,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        return PageResponse.from(
                auditLogs.findByActorOrderByCreatedAtDesc(
                        actor, PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 200))),
                AuditLogView::from);
    }

    /**
     * Reconciliation probe: across the whole double-entry ledger the debits and
     * credits must cancel out exactly. Anything other than zero means money was
     * created or destroyed and should page someone.
     */
    @GetMapping("/reconciliation")
    @Operation(summary = "Ledger integrity check and outbox backlog")
    public Map<String, Object> reconciliation() {
        BigDecimal net = ledger.netPostedAmount();
        return Map.of(
                "netPostedAmount", net,
                "balanced", net.compareTo(BigDecimal.ZERO) == 0,
                "pendingOutboxEvents", outbox.countPending());
    }

    public record AuditLogView(String actor,
                               String action,
                               String entityType,
                               String entityId,
                               String outcome,
                               String details,
                               String clientIp,
                               java.time.Instant createdAt) {

        static AuditLogView from(AuditLog log) {
            return new AuditLogView(log.getActor(), log.getAction(), log.getEntityType(),
                    log.getEntityId(), log.getOutcome().name(), log.getDetails(),
                    log.getClientIp(), log.getCreatedAt());
        }
    }
}
