package com.paytm.wallet.api;

import com.paytm.wallet.domain.TransferCommand;
import com.paytm.wallet.dto.TransferRequest;
import com.paytm.wallet.dto.TransferResponse;
import com.paytm.wallet.security.CallerIdentity;
import com.paytm.wallet.service.TransferService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;
    private final CallerIdentity callerIdentity;

    public TransferController(TransferService transferService, CallerIdentity callerIdentity) {
        this.transferService = transferService;
        this.callerIdentity = callerIdentity;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> createTransfer(@Valid @RequestBody TransferRequest body,
                                                           HttpServletRequest request) {
        String userKey = callerIdentity.userKey(request);
        TransferCommand command = new TransferCommand(
                body.from(),
                body.to(),
                body.amountPaise(),
                body.idempotencyKey()
        );
        // Always 200 for both first execution and an idempotent replay so retry responses are identical.
        return ResponseEntity.ok(TransferResponse.from(transferService.transfer(userKey, command)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransferResponse> getTransfer(@PathVariable UUID id,
                                                        HttpServletRequest request) {
        String userKey = callerIdentity.userKey(request);
        return ResponseEntity.ok(TransferResponse.from(transferService.getTransfer(userKey, id)));
    }
}
