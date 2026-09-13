package com.paytm.wallet.service;

import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.exception.ForbiddenException;
import com.paytm.wallet.exception.NotFoundException;
import com.paytm.wallet.observability.DomainMetrics;
import com.paytm.wallet.persistence.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;
    private final DomainMetrics metrics;
    private final long initialBalancePaise;

    public WalletService(WalletRepository walletRepository,
                         DomainMetrics metrics,
                         @Value("${wallet.initial-balance-paise}") long initialBalancePaise) {
        if (initialBalancePaise < 0) {
            throw new IllegalArgumentException("wallet.initial-balance-paise must be >= 0");
        }
        this.walletRepository = walletRepository;
        this.metrics = metrics;
        this.initialBalancePaise = initialBalancePaise;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Wallet getOrCreate(String userKey) {
        UUID candidateId = UUID.randomUUID();
        boolean created = walletRepository.tryCreate(candidateId, userKey, initialBalancePaise);
        Wallet wallet = walletRepository.findByUserKey(userKey)
                .orElseThrow(() -> new IllegalStateException("Wallet insert/select invariant failed"));

        if (created) {
            metrics.walletCreated();
            log.atInfo()
                    .addKeyValue("event", "wallet_created")
                    .addKeyValue("wallet_id", wallet.id())
                    .addKeyValue("balance_paise", wallet.balancePaise())
                    .log("wallet created");
        } else {
            metrics.walletExistingHit();
            log.atInfo()
                    .addKeyValue("event", "wallet_existing_returned")
                    .addKeyValue("wallet_id", wallet.id())
                    .log("existing wallet returned");
        }
        return wallet;
    }

    @Transactional(readOnly = true)
    public Wallet getOwnedWallet(String userKey, UUID walletId) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new NotFoundException("Wallet not found"));
        if (!wallet.userKey().equals(userKey)) {
            throw new ForbiddenException("Wallet does not belong to caller");
        }
        return wallet;
    }
}
