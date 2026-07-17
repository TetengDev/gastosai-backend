package com.teng.app.gastosai.dto;

import java.math.BigDecimal;
import java.util.List;

public record ObservabilityCost(
        BigDecimal todayCostUsd,
        long successToday,
        long failedToday,
        List<AiUsageSummaryItem> monthToDate
) {}
