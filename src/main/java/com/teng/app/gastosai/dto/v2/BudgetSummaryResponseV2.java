package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.BudgetSummaryResponse;

import java.util.List;

/** {@link BudgetSummaryResponse} with the money fields as integer centavos. */
public record BudgetSummaryResponseV2(
		String month,
		List<BudgetSummaryItemV2> items,
		Long totalBudgeted,
		Long totalSpent,
		Long safeToSpend,
		Long dailyAllowance
) {

	public static BudgetSummaryResponseV2 from(BudgetSummaryResponse v1) {
		return new BudgetSummaryResponseV2(
				v1.month(),
				v1.items() == null ? null : v1.items().stream().map(BudgetSummaryItemV2::from).toList(),
				Money.toCentavos(v1.totalBudgeted()),
				Money.toCentavos(v1.totalSpent()),
				Money.toCentavos(v1.safeToSpend()),
				Money.toCentavos(v1.dailyAllowance()));
	}
}
