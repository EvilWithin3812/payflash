package com.payflash.outbox;

public final class Topics {

    public static final String WALLET_TRANSACTIONS = "wallet.transactions";
    /** Flash-sale reservations waiting to become order rows. Absorbs the burst in front of Postgres. */
    public static final String SALE_ORDERS = "sale.orders";
    public static final String SALE_ORDERS_DLT = SALE_ORDERS + ".DLT";

    /** Account lifecycle events (account deleted) for services that keep per-user data. */
    public static final String USER_ACCOUNTS = "user.accounts";

    /** Payment requests asked, declined or cancelled (the notification service tells the other person). */
    public static final String PAYMENT_REQUESTS = "payment.requests";

    private Topics() {
    }
}
