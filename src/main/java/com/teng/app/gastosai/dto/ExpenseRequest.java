package com.teng.app.gastosai.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ExpenseRequest(
		@NotNull @DecimalMin(value = "0.0", inclusive = false)
		@Digits(integer = 15, fraction = 4) BigDecimal amount,
		@Size(max = 50) String category,
		LocalDateTime date,
		@NotBlank String description
) {
}
