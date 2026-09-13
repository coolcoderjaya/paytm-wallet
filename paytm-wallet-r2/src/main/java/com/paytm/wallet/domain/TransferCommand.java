package com.paytm.wallet.domain;

import java.util.UUID;

public record TransferCommand(
        UUID from,
        UUID to,
        long amountPaise,
        String idempotencyKey
) {}
