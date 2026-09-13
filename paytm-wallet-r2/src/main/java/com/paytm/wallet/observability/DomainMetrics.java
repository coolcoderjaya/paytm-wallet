package com.paytm.wallet.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class DomainMetrics {

    private final Counter transfersCreated;
    private final Counter transfersSucceeded;
    private final Counter transfersDeclinedInsufficientFunds;
    private final Counter idempotentReplays;
    private final Counter idempotencyConflicts;
    private final Counter walletsCreated;
    private final Counter walletGetOrCreateHits;

    public DomainMetrics(MeterRegistry registry) {
        this.transfersCreated = Counter.builder("wallet.transfers.created").register(registry);
        this.transfersSucceeded = Counter.builder("wallet.transfers.succeeded").register(registry);
        this.transfersDeclinedInsufficientFunds = Counter.builder("wallet.transfers.declined.insufficient_funds").register(registry);
        this.idempotentReplays = Counter.builder("wallet.transfers.idempotent.replays").register(registry);
        this.idempotencyConflicts = Counter.builder("wallet.transfers.idempotency.conflicts").register(registry);
        this.walletsCreated = Counter.builder("wallets.created").register(registry);
        this.walletGetOrCreateHits = Counter.builder("wallets.get_or_create.existing").register(registry);
    }

    public void transferCreated() { transfersCreated.increment(); }
    public void transferSucceeded() { transfersSucceeded.increment(); }
    public void transferDeclinedInsufficientFunds() { transfersDeclinedInsufficientFunds.increment(); }
    public void idempotentReplay() { idempotentReplays.increment(); }
    public void idempotencyConflict() { idempotencyConflicts.increment(); }
    public void walletCreated() { walletsCreated.increment(); }
    public void walletExistingHit() { walletGetOrCreateHits.increment(); }
}
