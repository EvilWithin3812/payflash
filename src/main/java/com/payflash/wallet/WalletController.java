package com.payflash.wallet;

import com.payflash.auth.AuthUser;
import com.payflash.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import com.payflash.wallet.WalletDtos.LedgerEntryResponse;
import com.payflash.wallet.WalletDtos.MoneyMovementResult;
import com.payflash.wallet.WalletDtos.TopUpRequest;
import com.payflash.wallet.WalletDtos.TransactionResponse;
import com.payflash.wallet.WalletDtos.TransferRequest;
import com.payflash.wallet.WalletDtos.WalletResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallet")
@Tag(name = "Wallet")
public class WalletController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final WalletService walletService;
    private final boolean simulatedTopUpEnabled;

    public WalletController(WalletService walletService,
                            @Value("${app.payments.simulated-topup-enabled:false}") boolean simulatedTopUpEnabled) {
        this.walletService = walletService;
        this.simulatedTopUpEnabled = simulatedTopUpEnabled;
    }

    @GetMapping
    @Operation(summary = "Get my wallet and balance")
    public WalletResponse myWallet(@AuthenticationPrincipal AuthUser user) {
        return walletService.getWallet(user.id());
    }

    @PostMapping("/topup")
    @Operation(summary = "Add money instantly with a simulated gateway (dev only; real money goes through /payments)")
    public ResponseEntity<TransactionResponse> topUp(@AuthenticationPrincipal AuthUser user,
                                                     @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
                                                     @Valid @RequestBody TopUpRequest request) {
        if (!simulatedTopUpEnabled) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "SIMULATED_TOPUP_DISABLED",
                    "Use /api/v1/payments/topup-orders to add money");
        }
        return toResponse(walletService.topUp(user.id(), request.amount(), clientKey(idempotencyKey)));
    }

    @PostMapping("/transfer")
    @Operation(summary = "Send money to another user by email")
    public ResponseEntity<TransactionResponse> transfer(@AuthenticationPrincipal AuthUser user,
                                                        @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
                                                        @Valid @RequestBody TransferRequest request) {
        return toResponse(walletService.transfer(user.id(), request, clientKey(idempotencyKey)));
    }

    @GetMapping("/transactions")
    @Operation(summary = "My transaction history (newest first)")
    public PagedModel<TransactionResponse> transactions(@AuthenticationPrincipal AuthUser user,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return new PagedModel<>(walletService.history(user.id(), page, size));
    }

    @GetMapping("/recipients")
    @Operation(summary = "People I sent money to recently (newest first)")
    public java.util.List<WalletDtos.Counterparty> recentRecipients(@AuthenticationPrincipal AuthUser user,
                                                                   @RequestParam(defaultValue = "8") int limit) {
        return walletService.recentRecipients(user.id(), limit);
    }

    @GetMapping("/ledger")
    @Operation(summary = "My ledger entries with running balance")
    public PagedModel<LedgerEntryResponse> ledger(@AuthenticationPrincipal AuthUser user,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return new PagedModel<>(walletService.ledger(user.id(), page, size));
    }

    /**
     * Client keys are limited to letters, digits, '-' and '_'. Internal operations use keys with a ':'
     * ("rzp:order_X", "sale:<hold>"), so a client can never claim one and block a Razorpay credit.
     */
    static String clientKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,100}")) {
            throw BusinessException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1-100 letters, digits, '-' or '_' (a UUID works)");
        }
        return key;
    }

    private static ResponseEntity<TransactionResponse> toResponse(MoneyMovementResult result) {
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .header(REPLAYED_HEADER, String.valueOf(result.replayed()))
                .body(result.transaction());
    }
}
