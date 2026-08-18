package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.BudgetResponse;

import java.math.BigDecimal;

/** {@link BudgetResponse} with the money fields as integer centavos. */
public record BudgetResponseV2(
		Long id,
		Long categoryId,
		String categoryName,
		String month,
		Long amountLimit,
		String currency,
		BigDecimal exchangeRate,
		Long amountLimitInBaseCurrency,
		boolean recurring
) {

	public static BudgetResponseV2 from(BudgetResponse v1) {
		return new BudgetResponseV2(
				v1.id(),
				v1.categoryId(),
				v1.categoryName(),
				v1.month(),
				Money.toCentavos(v1.amountLimit()),
				v1.currency(),
				v1.exchangeRate(),
				Money.toCentavos(v1.amountLimitInBaseCurrency()),
				v1.recurring());
	}
}
