package com.teng.app.gastosai.entity;

import com.teng.app.gastosai.config.PricingProperties;

import java.time.LocalDateTime;

public enum BillingPeriod {
    MONTHLY {
        @Override
        public LocalDateTime plus(LocalDateTime from) {
            return from.plusMonths(1);
        }

        @Override
        public int centavos(PricingProperties pricing) {
            return pricing.getPremiumMonthlyCentavos();
        }
    },
    ANNUAL {
        @Override
        public LocalDateTime plus(LocalDateTime from) {
            return from.plusYears(1);
        }

        @Override
        public int centavos(PricingProperties pricing) {
            return pricing.getPremiumAnnualCentavos();
        }
    };

    public abstract LocalDateTime plus(LocalDateTime from);

    public abstract int centavos(PricingProperties pricing);
}
