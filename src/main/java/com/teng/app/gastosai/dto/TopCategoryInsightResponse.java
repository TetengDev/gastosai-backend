package com.teng.app.gastosai.dto;

import java.math.BigDecimal;

public record TopCategoryInsightResponse(
        String month,
        String category,
        BigDecimal total,
        BigDecimal percentOfMonthTotal) {}