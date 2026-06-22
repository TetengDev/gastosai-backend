package com.teng.app.gastosai.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record BudgetRequest(
		@NotNull Long categoryId,
		@NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "must be a valid month in YYYY-MM format") String month,
		@NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal amountLimit,
		String currency,
		@DecimalMin(value = "0.0", inclusive = false, message = "must be greater than 0") BigDecimal exchangeRate,
		Boolean recurring
) {
}
