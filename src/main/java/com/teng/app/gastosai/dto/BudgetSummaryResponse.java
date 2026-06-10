package com.teng.app.gastosai.dto;

import java.math.BigDecimal;
import java.util.List;

public record BudgetSummaryResponse(
		String month,
		List<BudgetSummaryItem> items,
		BigDecimal totalBudgeted,
		BigDecimal totalSpent,
		BigDecimal safeToSpend,
		BigDecimal dailyAllowance
) {
}
