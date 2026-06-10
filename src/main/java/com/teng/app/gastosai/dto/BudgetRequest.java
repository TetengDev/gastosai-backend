package com.teng.app.gastosai.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record BudgetRequest(
		@NotNull Long categoryId,
		@NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String month,
		@NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal amountLimit
) {
}
