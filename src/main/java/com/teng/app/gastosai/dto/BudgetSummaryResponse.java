package com.teng.app.gastosai.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * @param percentUsed the month's {@code totalSpent} as a whole-number percentage of
 *                    {@code totalBudgeted}, rounded half-up and not capped at 100; 0 when nothing is
 *                    budgeted. Served so the clients stop computing it from the two totals (TEN-502).
 */
public record BudgetSummaryResponse(
		String month,
		List<BudgetSummaryItem> items,
		BigDecimal totalBudgeted,
		BigDecimal totalSpent,
		BigDecimal safeToSpend,
		BigDecimal dailyAllowance,
		int percentUsed
) {

	/** The pre-{@code percentUsed} arity, kept for callers that build a summary from its totals. */
	public BudgetSummaryResponse(String month, List<BudgetSummaryItem> items, BigDecimal totalBudgeted,
			BigDecimal totalSpent, BigDecimal safeToSpend, BigDecimal dailyAllowance) {
		this(month, items, totalBudgeted, totalSpent, safeToSpend, dailyAllowance,
				percentUsed(totalSpent, totalBudgeted));
	}

	/**
	 * {@code spent} as a whole-number percentage of {@code budgeted}, rounded once, half-up — the
	 * {@code Math.round(spent * 100 / budgeted)} both clients computed before this field existed.
	 */
	public static int percentUsed(BigDecimal spent, BigDecimal budgeted) {
		if (budgeted == null || spent == null || budgeted.signum() <= 0) {
			return 0;
		}
		return spent.multiply(BigDecimal.valueOf(100)).divide(budgeted, 0, RoundingMode.HALF_UP).intValueExact();
	}
}
