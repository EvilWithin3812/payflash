package com.payflash.wallet;

public enum TransactionType {
    TOPUP,
    TRANSFER,
    /** Wallet pays a merchant (e.g. a flash-sale order). Money leaves the system: no destination wallet. */
    PURCHASE
}
