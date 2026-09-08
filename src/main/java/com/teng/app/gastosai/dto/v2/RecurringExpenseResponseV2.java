package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.RecurringExpenseResponse;
import com.teng.app.gastosai.dto.RecurringExpenseWithBase;
import com.teng.app.gastosai.entity.Frequency;

import java.math.BigDecimal;

/**
 * {@link RecurringExpenseResponse} with {@code amount} as integer centavos.
 *
 * <p>{@code amountInBaseCurrency} is the same amount converted with the stored
 * {@code exchangeRate}, matching what {@link ExpenseResponseV2} and {@link BudgetResponseV2}
 * already serve. Without it a client showing a foreign-currency bill in pesos had to multiply the
 * amount by the rate itself, which is a currency conversion in float arithmetic on money.
 *
 * <p>The conversion is done by the service, from the stored amount at its full
 * {@code NUMERIC(19,4)} precision — not here from {@link RecurringExpenseResponse#amount()}, which
 * has already been rounded to two places for display.
 */
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
		BigDecimal exchangeRate,
		Long amountInBaseCurrency
) {

	public static RecurringExpenseResponseV2 from(RecurringExpenseWithBase withBase) {
		RecurringExpenseResponse v1 = withBase.response();
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
				v1.exchangeRate(),
				Money.toCentavos(withBase.amountInBaseCurrency()));
	}
}
