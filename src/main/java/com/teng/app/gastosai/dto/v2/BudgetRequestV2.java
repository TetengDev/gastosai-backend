package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.BudgetRequest;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/** {@link BudgetRequest} with {@code amountLimit} as integer centavos. */
public record BudgetRequestV2(
		@NotNull Long categoryId,
		@NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "must be a valid month in YYYY-MM format") String month,
		@NotNull @Min(1) @Max(Money.MAX_CENTAVOS) Long amountLimit,
		String currency,
		@DecimalMin(value = "0.0", inclusive = false, message = "must be greater than 0") BigDecimal exchangeRate,
		Boolean recurring
) {

	public BudgetRequest toV1() {
		return new BudgetRequest(categoryId, month, Money.toDecimal(amountLimit), currency, exchangeRate, recurring);
	}
}
