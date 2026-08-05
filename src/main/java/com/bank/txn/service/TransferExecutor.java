package com.bank.txn.service;

import com.bank.txn.domain.Account;
import com.bank.txn.domain.EntryDirection;
import com.bank.txn.domain.LedgerEntry;
import com.bank.txn.domain.Transfer;
import com.bank.txn.error.AccessDeniedForAccountException;
import com.bank.txn.error.ResourceNotFoundException;
import com.bank.txn.messaging.OutboxRecorder;
import com.bank.txn.messaging.event.TransactionEvent;
import com.bank.txn.repository.AccountRepository;
import com.bank.txn.repository.LedgerEntryRepository;
import com.bank.txn.repository.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The transactional core of a transfer.
 *
 * <p>Kept in its own bean so that {@link TransferService} can retry the whole
 * unit of work: a retry has to start a brand new transaction with a fresh
 * persistence context, which is impossible if the retry loop lives inside the
 * transaction it is retrying.
 */
@Service
public class TransferExecutor {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerEntryRepository ledger;
    private final OutboxRecorder outbox;

    public TransferExecutor(AccountRepository accounts,
                            TransferRepository transfers,
                            LedgerEntryRepository ledger,
                            OutboxRecorder outbox) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.ledger = ledger;
        this.outbox = outbox;
    }

    /**
     * Moves the money, posts both ledger legs and queues the domain event, all
     * all in one database transaction, so the service can never be observed
     * with a debit that has no matching credit.
     */
    @Transactional
    public Transfer execute(TransferCommand command) {
        Account source = load(command.sourceAccountNumber());
        Account target = load(command.targetAccountNumber());

        if (!command.admin() && !source.getOwnerId().equals(command.initiatorId())) {
            throw new AccessDeniedForAccountException(command.sourceAccountNumber());
        }

        Transfer transfer = new Transfer(
                command.reference(),
                source.getId(),
                target.getId(),
                command.amount(),
                command.currency(),
                command.description(),
                command.idempotencyKey(),
                command.initiatedBy());
        transfers.saveAndFlush(transfer);

        source.debit(command.amount(), command.currency());
        target.credit(command.amount(), command.currency());

        // Flush the two account UPDATEs in a globally consistent order. A→B and
        // B→A transfers running at the same time would otherwise be able to
        // grab each other's row locks and deadlock in the database.
        boolean sourceFirst = source.getId().compareTo(target.getId()) < 0;
        accounts.saveAndFlush(sourceFirst ? source : target);
        accounts.saveAndFlush(sourceFirst ? target : source);

        ledger.save(new LedgerEntry(transfer.getId(), source.getId(),
                EntryDirection.DEBIT, command.amount(), source.getBalance()));
        ledger.save(new LedgerEntry(transfer.getId(), target.getId(),
                EntryDirection.CREDIT, command.amount(), target.getBalance()));

        transfer.markCompleted();
        transfers.saveAndFlush(transfer);

        outbox.record("Transfer", transfer.getId().toString(),
                TransactionEvent.TRANSFER_COMPLETED, TransactionEvent.from(transfer));

        return transfer;
    }

    /**
     * Persists the rejected attempt after the business transaction has rolled
     * back. Runs in a fresh transaction so the record survives; a declined
     * transfer is still something customers and auditors need to see.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Transfer> recordFailure(TransferCommand command, String reason) {
        Optional<Account> source = accounts.findByAccountNumber(command.sourceAccountNumber());
        Optional<Account> target = accounts.findByAccountNumber(command.targetAccountNumber());
        if (source.isEmpty() || target.isEmpty()
                || source.get().getId().equals(target.get().getId())) {
            // Nothing referentially valid to attach the row to; the audit log
            // is the only record in this case.
            return Optional.empty();
        }

        Transfer transfer = new Transfer(
                command.reference(),
                source.get().getId(),
                target.get().getId(),
                command.amount(),
                command.currency(),
                command.description(),
                command.idempotencyKey(),
                command.initiatedBy());
        transfer.markFailed(reason);
        transfers.saveAndFlush(transfer);

        outbox.record("Transfer", transfer.getId().toString(),
                TransactionEvent.TRANSFER_FAILED, TransactionEvent.from(transfer));

        return Optional.of(transfer);
    }

    private Account load(String accountNumber) {
        return accounts.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountNumber));
    }
}
