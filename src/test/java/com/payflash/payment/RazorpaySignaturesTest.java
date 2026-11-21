package com.payflash.payment;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpaySignaturesTest {

    // Expected values computed independently with:
    //   printf '<data>' | openssl dgst -sha256 -hmac '<secret>'
    private static final String KEY_SECRET = "EnLs21M47BllR3X8PSFtjtbd";
    private static final String CHECKOUT_SIG = "0d4e745a1838664ad6c9c9902212a32d627d68e917290b0ad5f08ff4561bc50f";
    private static final byte[] WEBHOOK_BODY = "{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);
    private static final String WEBHOOK_SIG = "4f463a57dd128675850163391f0311888616d57bccca75c774c9cdb28134f851";

    @Test
    void acceptsValidCheckoutSignature() {
        assertThat(RazorpaySignatures.isValidPayment("order_IEIaMR65cu6nz3", "pay_IH4NVgf4Dreq1l", CHECKOUT_SIG,
                KEY_SECRET)).isTrue();
    }

    @Test
    void rejectsCheckoutSignatureForAnotherPaymentOrSecret() {
        assertThat(RazorpaySignatures.isValidPayment("order_IEIaMR65cu6nz3", "pay_OTHER", CHECKOUT_SIG, KEY_SECRET))
                .isFalse();
        assertThat(RazorpaySignatures.isValidPayment("order_IEIaMR65cu6nz3", "pay_IH4NVgf4Dreq1l", CHECKOUT_SIG,
                "wrong-secret")).isFalse();
        assertThat(RazorpaySignatures.isValidPayment("order_IEIaMR65cu6nz3", "pay_IH4NVgf4Dreq1l", null, KEY_SECRET))
                .isFalse();
    }

    @Test
    void verifiesWebhookOverExactBytes() {
        assertThat(RazorpaySignatures.isValidWebhook(WEBHOOK_BODY, WEBHOOK_SIG, "whsec_test")).isTrue();
        // One extra space changes the HMAC: this is why the controller takes the raw body.
        byte[] reformatted = "{\"event\": \"payment.captured\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(RazorpaySignatures.isValidWebhook(reformatted, WEBHOOK_SIG, "whsec_test")).isFalse();
    }
}
