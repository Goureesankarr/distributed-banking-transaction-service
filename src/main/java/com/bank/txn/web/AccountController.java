package com.bank.txn.web;

import com.bank.txn.domain.Transfer;
import com.bank.txn.service.AccountService;
import com.bank.txn.service.TransferService;
import com.bank.txn.web.dto.AccountView;
import com.bank.txn.web.dto.CreateAccountRequest;
import com.bank.txn.web.dto.PageResponse;
import com.bank.txn.web.dto.TransferView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts")
public class AccountController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AccountService accounts;
    private final TransferService transfers;

    public AccountController(AccountService accounts, TransferService transfers) {
        this.accounts = accounts;
        this.transfers = transfers;
    }

    @PostMapping
    @Operation(summary = "Open an account for the authenticated customer")
    public ResponseEntity<AccountView> open(Authentication auth,
                                            @Valid @RequestBody CreateAccountRequest request) {
        AccountView view = accounts.open(CurrentUser.username(auth), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping
    @Operation(summary = "List the authenticated customer's accounts")
    public List<AccountView> mine(Authentication auth) {
        return accounts.listOwned(CurrentUser.username(auth));
    }

    @GetMapping("/{accountNumber}")
    @Operation(summary = "Fetch a single account, served from cache when warm")
    public AccountView get(Authentication auth, @PathVariable String accountNumber) {
        return accounts.get(CurrentUser.username(auth), accountNumber, CurrentUser.isAdmin(auth));
    }

    @GetMapping("/{accountNumber}/transfers")
    @Operation(summary = "Paged transfer history for an account, newest first")
    public PageResponse<TransferView> history(
            Authentication auth,
            @PathVariable String accountNumber,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        PageRequest pageRequest = PageRequest.of(
                Math.max(0, page),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        return PageResponse.from(
                transfers.history(CurrentUser.username(auth), accountNumber, from, to,
                        CurrentUser.isAdmin(auth), pageRequest),
                TransferView::from);
    }
}
