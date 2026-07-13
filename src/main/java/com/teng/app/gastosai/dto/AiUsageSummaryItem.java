package com.teng.app.gastosai.dto;

import java.math.BigDecimal;

public record AiUsageSummaryItem(
        String feature,
        String model,
        long requests,
        Long inputTokens,
        Long outputTokens,
        BigDecimal estimatedCostUsd
) {}
