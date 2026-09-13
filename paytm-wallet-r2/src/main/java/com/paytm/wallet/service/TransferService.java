package com.paytm.wallet.service;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.TransferCommand;
import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.exception.BadRequestException;
import com.paytm.wallet.exception.ForbiddenException;
import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.exception.NotFoundException;
import com.paytm.wallet.observability.DomainMetrics;
import com.paytm.wallet.persistence.TransferRepository;
import com.paytm.wallet.persistence.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.UUID;

@Service
public class TransferService {

    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final Comparator<UUID> LOCK_ORDER = Comparator.comparing(UUID::toString);

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final DomainMetrics metrics;

    public TransferService(WalletRepository walletRepository,
                           TransferRepository transferRepository,
                           DomainMetrics metrics) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.metrics = metrics;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Transfer transfer(String callerUserKey, TransferCommand command) {
        validate(command);

        // Fast-path an already committed key so an exact replay returns the original result even
        // before any wallet validation. Correctness does NOT depend on this read; the unique
        // constraint + INSERT ... ON CONFLICT below is still the authority for concurrent races.
        Transfer alreadyCommitted = transferRepository.findByIdempotencyKey(command.idempotencyKey()).orElse(null);
        if (alreadyCommitted != null) {
            return resolveExisting(callerUserKey, command, alreadyCommitted);
        }

        // Wallet identity/ownership is immutable in this exercise (there is no wallet delete/transfer-owner API),
        // so these pre-checks are safe and allow clean 404/403 responses before claiming a new key.
        Wallet sourceSnapshot = walletRepository.findById(command.from())
                .orElseThrow(() -> new NotFoundException("Source wallet not found"));
        walletRepository.findById(command.to())
                .orElseThrow(() -> new NotFoundException("Destination wallet not found"));
        if (!sourceSnapshot.userKey().equals(callerUserKey)) {
            throw new ForbiddenException("Source wallet does not belong to caller");
        }
    // Lock the money rows FIRST, always in deterministic UUID order.
    //
    // Important: transfers has foreign keys to wallets. If we INSERT the transfer
    // first, PostgreSQL's FK checks can acquire row-level locks before our
    // SELECT ... FOR UPDATE locks. Under A->B and B->A contention that can
    // undermine the deterministic ordering.
    //
    // Lock first -> idempotency claim -> money movement, all in the SAME tx.
    lockBothWallets(command.from(), command.to());

    UUID transferId = UUID.randomUUID();

    boolean claimed = transferRepository.tryInsertPending(
            transferId,
            command.idempotencyKey(),
            callerUserKey,
            command.from(),
            command.to(),
            command.amountPaise()
    );

    if (!claimed) {
        return handleReplay(callerUserKey, command);
    }

    metrics.transferCreated();

    log.atInfo()
            .addKeyValue("event", "transfer_created")
            .addKeyValue("transfer_id", transferId)
            .addKeyValue("from_wallet_id", command.from())
            .addKeyValue("to_wallet_id", command.to())
            .addKeyValue("amount_paise", command.amountPaise())
            .log("transfer created");

        int debited = walletRepository.debitIfSufficient(command.from(), command.amountPaise());
        if (debited == 0) {
            transferRepository.markDeclined(transferId, INSUFFICIENT_FUNDS);
            metrics.transferDeclinedInsufficientFunds();
            log.atInfo()
                    .addKeyValue("event", "transfer_declined")
                    .addKeyValue("transfer_id", transferId)
                    .addKeyValue("reason", INSUFFICIENT_FUNDS)
                    .addKeyValue("amount_paise", command.amountPaise())
                    .log("transfer declined");
            return requiredTransfer(transferId);
        }

        log.atInfo()
                .addKeyValue("event", "transfer_debited")
                .addKeyValue("transfer_id", transferId)
                .addKeyValue("wallet_id", command.from())
                .addKeyValue("amount_paise", command.amountPaise())
                .log("source wallet debited");

        int credited = walletRepository.credit(command.to(), command.amountPaise());
        if (credited != 1) {
            throw new IllegalStateException("Destination wallet disappeared while locked");
        }

        log.atInfo()
                .addKeyValue("event", "transfer_credited")
                .addKeyValue("transfer_id", transferId)
                .addKeyValue("wallet_id", command.to())
                .addKeyValue("amount_paise", command.amountPaise())
                .log("destination wallet credited");

        transferRepository.markSucceeded(transferId);
        metrics.transferSucceeded();

        log.atInfo()
                .addKeyValue("event", "transfer_succeeded")
                .addKeyValue("transfer_id", transferId)
                .log("transfer succeeded");

        return requiredTransfer(transferId);
    }

    @Transactional(readOnly = true)
    public Transfer getTransfer(String callerUserKey, UUID transferId) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new NotFoundException("Transfer not found"));
        if (!transfer.initiatedByUserKey().equals(callerUserKey)) {
            throw new ForbiddenException("Transfer does not belong to caller");
        }
        return transfer;
    }

    private Transfer handleReplay(String callerUserKey, TransferCommand command) {
        Transfer existing = transferRepository.findByIdempotencyKey(command.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Idempotency conflict row not visible after ON CONFLICT"));
        return resolveExisting(callerUserKey, command, existing);
    }

    private Transfer resolveExisting(String callerUserKey, TransferCommand command, Transfer existing) {
        if (!existing.sameRequest(callerUserKey, command.from(), command.to(), command.amountPaise())) {
            metrics.idempotencyConflict();
            log.atWarn()
                    .addKeyValue("event", "idempotency_conflict")
                    .addKeyValue("idempotency_key", command.idempotencyKey())
                    .addKeyValue("existing_transfer_id", existing.id())
                    .log("idempotency key reused with a different request");
            throw new IdempotencyConflictException("Idempotency key was already used with a different request body");
        }

        metrics.idempotentReplay();
        log.atInfo()
                .addKeyValue("event", "idempotent_replay_hit")
                .addKeyValue("idempotency_key", command.idempotencyKey())
                .addKeyValue("transfer_id", existing.id())
                .log("idempotent replay returned original transfer");
        return existing;
    }

    private void lockBothWallets(UUID from, UUID to) {
        UUID first = LOCK_ORDER.compare(from, to) <= 0 ? from : to;
        UUID second = first.equals(from) ? to : from;

        walletRepository.lockById(first)
                .orElseThrow(() -> new NotFoundException("Wallet not found while acquiring lock"));
        walletRepository.lockById(second)
                .orElseThrow(() -> new NotFoundException("Wallet not found while acquiring lock"));

        log.atDebug()
                .addKeyValue("event", "wallets_locked")
                .addKeyValue("first_wallet_id", first)
                .addKeyValue("second_wallet_id", second)
                .log("wallet rows locked in deterministic order");
    }

    private void validate(TransferCommand command) {
        if (command.from().equals(command.to())) {
            throw new BadRequestException("Source and destination wallets must be different");
        }
        if (command.amountPaise() <= 0) {
            throw new BadRequestException("amount_paise must be positive");
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new BadRequestException("idempotency_key is required");
        }
    }

    private Transfer requiredTransfer(UUID transferId) {
        return transferRepository.findById(transferId)
                .orElseThrow(() -> new IllegalStateException("Transfer row disappeared"));
    }
}
