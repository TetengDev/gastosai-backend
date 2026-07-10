package com.teng.app.gastosai;

import com.teng.app.gastosai.config.PricingProperties;
import com.teng.app.gastosai.entity.BillingPeriod;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BillingPeriodTest {

    @Test
    void monthlyPlusAddsOneMonth() {
        LocalDateTime base = LocalDateTime.of(2025, 1, 15, 10, 0);
        assertThat(BillingPeriod.MONTHLY.plus(base)).isEqualTo(LocalDateTime.of(2025, 2, 15, 10, 0));
    }

    @Test
    void annualPlusAddsOneYear() {
        LocalDateTime base = LocalDateTime.of(2025, 3, 1, 0, 0);
        assertThat(BillingPeriod.ANNUAL.plus(base)).isEqualTo(LocalDateTime.of(2026, 3, 1, 0, 0));
    }

    @Test
    void monthlyAmountMatchesPricing() {
        PricingProperties props = new PricingProperties();
        props.setPremiumMonthlyCentavos(14900);
        assertThat(BillingPeriod.MONTHLY.centavos(props)).isEqualTo(14900);
    }

    @Test
    void annualAmountMatchesPricing() {
        PricingProperties props = new PricingProperties();
        props.setPremiumAnnualCentavos(129000);
        assertThat(BillingPeriod.ANNUAL.centavos(props)).isEqualTo(129000);
    }
}
