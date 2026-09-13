package com.paytm.wallet.dto;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.TransferStatus;

import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        String idempotencyKey,
        UUID from,
        UUID to,
        long amountPaise,
        TransferStatus status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.id(),
                transfer.idempotencyKey(),
                transfer.fromWalletId(),
                transfer.toWalletId(),
                transfer.amountPaise(),
                transfer.status(),
                transfer.failureReason(),
                transfer.createdAt(),
                transfer.updatedAt()
        );
    }
}
