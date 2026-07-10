package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.BillingPeriod;
import com.teng.app.gastosai.entity.PlanKey;

public record PricingItem(
        PlanKey planKey,
        BillingPeriod period,
        int amountCentavos,
        String currency
) {}
