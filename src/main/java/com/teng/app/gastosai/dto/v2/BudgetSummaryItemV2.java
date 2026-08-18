package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.BudgetSummaryItem;

import java.math.BigDecimal;

/**
 * {@link BudgetSummaryItem} with the money fields as integer centavos.
 *
 * <p>{@code percentUsed} stays a decimal. It is a proportion, not an amount, so rounding it to
 * "centavos" would be meaningless — the same line {@code V23} drew when it gave every money column
 * a centavos twin and left the rates and percentages alone.
 */
public record BudgetSummaryItemV2(
		Long categoryId,
		String categoryName,
		Long budgeted,
		Long spent,
		Long remaining,
		BigDecimal percentUsed,
		String status
) {

	public static BudgetSummaryItemV2 from(BudgetSummaryItem v1) {
		return new BudgetSummaryItemV2(
				v1.categoryId(),
				v1.categoryName(),
				Money.toCentavos(v1.budgeted()),
				Money.toCentavos(v1.spent()),
				Money.toCentavos(v1.remaining()),
				v1.percentUsed(),
				v1.status());
	}
}
