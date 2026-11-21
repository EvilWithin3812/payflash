package com.payflash.payment;

/** The slice of a payment provider PayFlash needs. Tests replace it with a mock; no network calls. */
public interface PaymentGateway {

    /**
     * Creates an order the customer can pay through the provider's checkout.
     *
     * @param amountMinor amount in the smallest currency unit (paise for INR)
     * @param receipt     our own reference, max 40 chars
     */
    GatewayOrder createOrder(long amountMinor, String currency, String receipt);

    record GatewayOrder(String id, long amountMinor, String currency, String status) {
    }
}
