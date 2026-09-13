package com.paytm.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record TransferRequest(
        @NotNull UUID from,
        @NotNull UUID to,
        @Positive long amountPaise,
        @NotBlank @Size(max = 128) String idempotencyKey
) {}
