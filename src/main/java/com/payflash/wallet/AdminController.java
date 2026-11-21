package com.payflash.wallet;

import com.payflash.wallet.WalletDtos.ReconciliationReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin")
public class AdminController {

    private final WalletService walletService;

    public AdminController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping("/reconciliation")
    @Operation(summary = "Check every wallet balance against the ledger (ADMIN only)")
    public ReconciliationReport reconcile() {
        return walletService.reconcile();
    }
}
