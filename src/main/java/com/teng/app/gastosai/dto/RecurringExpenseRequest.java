package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.Frequency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record RecurringExpenseRequest(
		@NotBlank @Size(max = 100) String name,
		@NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount,
		String categoryName,
		@NotNull Frequency frequency,
		Integer dayOfMonth,
		Integer dayOfWeek,
		Integer monthOfYear,
		Boolean active,
		String currency,
		BigDecimal exchangeRate
) {
}
