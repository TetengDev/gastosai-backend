package com.teng.app.gastosai.dto;

import java.math.BigDecimal;

public record BudgetResponse(
		Long id,
		Long categoryId,
		String categoryName,
		String month,
		BigDecimal amountLimit,
		String currency,
		BigDecimal exchangeRate,
		BigDecimal amountLimitInBaseCurrency
) {
}
