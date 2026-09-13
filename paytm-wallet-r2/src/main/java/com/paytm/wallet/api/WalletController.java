package com.paytm.wallet.api;

import com.paytm.wallet.dto.WalletResponse;
import com.paytm.wallet.security.CallerIdentity;
import com.paytm.wallet.service.WalletService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;
    private final CallerIdentity callerIdentity;

    public WalletController(WalletService walletService, CallerIdentity callerIdentity) {
        this.walletService = walletService;
        this.callerIdentity = callerIdentity;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> getOrCreate(HttpServletRequest request) {
        String userKey = callerIdentity.userKey(request);
        return ResponseEntity.ok(WalletResponse.from(walletService.getOrCreate(userKey)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WalletResponse> getWallet(@PathVariable UUID id, HttpServletRequest request) {
        String userKey = callerIdentity.userKey(request);
        return ResponseEntity.ok(WalletResponse.from(walletService.getOwnedWallet(userKey, id)));
    }
}
