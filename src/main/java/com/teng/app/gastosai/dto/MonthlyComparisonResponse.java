package com.teng.app.gastosai.dto;

import java.math.BigDecimal;

public record MonthlyComparisonResponse(
        String month,
        BigDecimal currentTotal,
        BigDecimal previousTotal,
        BigDecimal changePercent
) {
}
