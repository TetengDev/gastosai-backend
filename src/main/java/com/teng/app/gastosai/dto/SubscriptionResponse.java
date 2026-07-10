package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.BillingPeriod;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.SubscriptionStatus;

import java.time.LocalDateTime;

public record SubscriptionResponse(
        PlanKey plan,
        SubscriptionStatus status,
        LocalDateTime currentPeriodEnd,
        BillingPeriod billingPeriod
) {}
