package com.payflash.payment;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Razorpay signature checks (HMAC-SHA256, hex encoded).
 * <ul>
 *   <li>Checkout: HMAC(order_id + "|" + payment_id, key_secret)</li>
 *   <li>Webhook: HMAC(raw request body, webhook_secret). It must be the exact bytes received;
 *       re-serialising parsed JSON would change whitespace/order and break the signature.</li>
 * </ul>
 */
public final class RazorpaySignatures {

    private RazorpaySignatures() {
    }

    public static boolean isValidPayment(String orderId, String paymentId, String signature, String keySecret) {
        if (orderId == null || paymentId == null) {
            return false;
        }
        return matches(hmacHex((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8), keySecret), signature);
    }

    public static boolean isValidWebhook(byte[] rawBody, String signature, String webhookSecret) {
        return rawBody != null && matches(hmacHex(rawBody, webhookSecret), signature);
    }

    static String hmacHex(byte[] data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** Constant-time comparison, so response timing doesn't reveal how many characters matched. */
    private static boolean matches(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), actual.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }
}
