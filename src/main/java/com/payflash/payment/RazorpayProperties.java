package com.payflash.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Razorpay test-mode credentials. Never commit real values: set them as environment variables
 * (RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET, RAZORPAY_WEBHOOK_SECRET).
 */
@ConfigurationProperties(prefix = "app.razorpay")
public record RazorpayProperties(String keyId, String keySecret, String webhookSecret, String baseUrl) {

    public RazorpayProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.razorpay.com";
        }
    }

    public boolean apiConfigured() {
        return notBlank(keyId) && notBlank(keySecret);
    }

    public boolean webhookConfigured() {
        return notBlank(webhookSecret);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
