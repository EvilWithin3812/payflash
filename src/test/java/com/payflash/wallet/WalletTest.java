package com.payflash.wallet;

import com.payflash.common.BusinessException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletTest {

    @Test
    void creditAndDebitAdjustBalance() {
        Wallet w = new Wallet(1L);
        w.credit(new BigDecimal("100.00"));
        w.debit(new BigDecimal("40.25"));
        assertThat(w.getBalance()).isEqualByComparingTo("59.75");
    }

    @Test
    void debitBeyondBalanceIsRejected() {
        Wallet w = new Wallet(1L);
        w.credit(new BigDecimal("10"));
        assertThatThrownBy(() -> w.debit(new BigDecimal("10.01")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Insufficient");
        assertThat(w.getBalance()).isEqualByComparingTo("10");
    }
}
