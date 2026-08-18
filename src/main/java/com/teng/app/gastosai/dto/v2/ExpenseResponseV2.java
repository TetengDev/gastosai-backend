package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.ExpenseResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@link ExpenseResponse} with the money fields as integer centavos.
 *
 * <p>Money is boxed {@code Long} rather than {@code long} throughout the v2 responses: v1 serves
 * these as a nullable {@code BigDecimal}, and unboxing an absent amount into {@code 0} would report
 * a value the v1 response never claimed. {@code exchangeRate} stays decimal — a rate is not money.
 */
public record ExpenseResponseV2(
		Long id,
		Long amount,
		String category,
		LocalDateTime date,
		String description,
		String expenseType,
		boolean reimbursable,
		String currency,
		BigDecimal exchangeRate,
		Long amountInBaseCurrency
) {

	public static ExpenseResponseV2 from(ExpenseResponse v1) {
		return new ExpenseResponseV2(
				v1.id(),
				Money.toCentavos(v1.amount()),
				v1.category(),
				v1.date(),
				v1.description(),
				v1.expenseType(),
				v1.reimbursable(),
				v1.currency(),
				v1.exchangeRate(),
				Money.toCentavos(v1.amountInBaseCurrency()));
	}
}
