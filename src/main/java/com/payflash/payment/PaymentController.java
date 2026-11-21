package com.payflash.payment;

import com.payflash.auth.AuthUser;
import com.payflash.payment.PaymentDtos.CreateTopUpOrderRequest;
import com.payflash.payment.PaymentDtos.TopUpOrderResponse;
import com.payflash.payment.PaymentDtos.VerifyPaymentRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments (Razorpay)")
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/topup-orders")
    @Operation(summary = "Create a Razorpay order to add money to my wallet")
    public ResponseEntity<TopUpOrderResponse> createOrder(@AuthenticationPrincipal AuthUser user,
                                                          @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                          @Valid @RequestBody CreateTopUpOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(payments.createTopUpOrder(user.id(), request.amount(), idempotencyKey));
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "Get one of my payment orders (poll this until status = PAID)")
    public TopUpOrderResponse getOrder(@AuthenticationPrincipal AuthUser user, @PathVariable String orderId) {
        return payments.getOrder(user.id(), orderId);
    }

    @PostMapping("/verify")
    @Operation(summary = "Confirm a Checkout payment with its signature; credits the wallet once")
    public TopUpOrderResponse verify(@AuthenticationPrincipal AuthUser user,
                                     @Valid @RequestBody VerifyPaymentRequest request) {
        return payments.verifyCheckout(user.id(), request);
    }

    /** Raw bytes, not a parsed object: the HMAC is computed over exactly what Razorpay sent. */
    @PostMapping("/webhook")
    @Operation(summary = "Razorpay webhook (server-to-server, HMAC-signed)")
    public Map<String, String> webhook(@RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
                                       @RequestBody byte[] body) {
        payments.handleWebhook(body, signature);
        return Map.of("status", "ok");
    }
}
