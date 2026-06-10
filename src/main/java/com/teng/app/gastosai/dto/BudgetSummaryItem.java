package com.teng.app.gastosai.dto;

import java.math.BigDecimal;

public record BudgetSummaryItem(
		Long categoryId,
		String categoryName,
		BigDecimal budgeted,
		BigDecimal spent,
		BigDecimal remaining,
		BigDecimal percentUsed,
		String status
) {
}
