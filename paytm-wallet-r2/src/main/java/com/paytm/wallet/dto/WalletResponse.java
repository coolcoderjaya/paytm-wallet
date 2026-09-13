package com.paytm.wallet.dto;

import com.paytm.wallet.domain.Wallet;

import java.util.UUID;

public record WalletResponse(UUID walletId, long balancePaise) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(wallet.id(), wallet.balancePaise());
    }
}
