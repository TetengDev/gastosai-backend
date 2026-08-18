package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.RecurringExpenseRequest;
import com.teng.app.gastosai.entity.Frequency;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** {@link RecurringExpenseRequest} with {@code amount} as integer centavos. */
public record RecurringExpenseRequestV2(
		@NotBlank @Size(max = 100) String name,
		@NotNull @Min(1) Long amount,
		String categoryName,
		@NotNull Frequency frequency,
		Integer dayOfMonth,
		Integer dayOfWeek,
		Integer monthOfYear,
		Boolean active,
		String currency,
		BigDecimal exchangeRate
) {

	public RecurringExpenseRequest toV1() {
		return new RecurringExpenseRequest(name, Money.toDecimal(amount), categoryName, frequency,
				dayOfMonth, dayOfWeek, monthOfYear, active, currency, exchangeRate);
	}
}
