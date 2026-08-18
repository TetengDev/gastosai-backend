package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.RecurringExpenseResponse;
import com.teng.app.gastosai.entity.Frequency;

import java.math.BigDecimal;

/** {@link RecurringExpenseResponse} with {@code amount} as integer centavos. */
public record RecurringExpenseResponseV2(
		Long id,
		String name,
		Long amount,
		String categoryName,
		Frequency frequency,
		Integer dayOfMonth,
		Integer dayOfWeek,
		Integer monthOfYear,
		boolean active,
		String currency,
		BigDecimal exchangeRate
) {

	public static RecurringExpenseResponseV2 from(RecurringExpenseResponse v1) {
		return new RecurringExpenseResponseV2(
				v1.id(),
				v1.name(),
				Money.toCentavos(v1.amount()),
				v1.categoryName(),
				v1.frequency(),
				v1.dayOfMonth(),
				v1.dayOfWeek(),
				v1.monthOfYear(),
				v1.active(),
				v1.currency(),
				v1.exchangeRate());
	}
}
