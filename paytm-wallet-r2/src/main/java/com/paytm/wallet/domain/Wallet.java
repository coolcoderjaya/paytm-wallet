package com.paytm.wallet.domain;

import java.time.Instant;
import java.util.UUID;

public record Wallet(
        UUID id,
        String userKey,
        long balancePaise,
        Instant createdAt,
        Instant updatedAt
) {}
