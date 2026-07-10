package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.BillingPeriod;
import jakarta.validation.constraints.NotNull;

public record CheckoutRequest(@NotNull BillingPeriod period) {}
