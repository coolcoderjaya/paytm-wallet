package com.paytm.wallet.domain;

import java.time.Instant;
import java.util.UUID;

public record Transfer(
        UUID id,
        String idempotencyKey,
        String initiatedByUserKey,
        UUID fromWalletId,
        UUID toWalletId,
        long amountPaise,
        TransferStatus status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    public boolean sameRequest(String callerUserKey, UUID from, UUID to, long amount) {
        return initiatedByUserKey.equals(callerUserKey)
                && fromWalletId.equals(from)
                && toWalletId.equals(to)
                && amountPaise == amount;
    }
}
