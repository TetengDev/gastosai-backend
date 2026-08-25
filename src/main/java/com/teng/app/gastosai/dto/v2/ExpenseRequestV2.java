package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.ExpenseRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@link ExpenseRequest} with {@code amount} as integer centavos.
 *
 * <p>{@code exchangeRate} stays a decimal: a rate is not money and does not round to centavos —
 * the same reason {@code V23} left the exchange-rate columns alone.
 */
public record ExpenseRequestV2(
		@NotNull @Min(1) @Max(Money.MAX_CENTAVOS) Long amount,
		@Size(max = 50) String category,
		LocalDateTime date,
		@NotBlank String description,
		String expenseType,
		Boolean reimbursable,
		@Size(max = 3) String currency,
		@DecimalMin("0.000001") @Digits(integer = 13, fraction = 6) BigDecimal exchangeRate,
		@Schema(description = "How this expense was created. Only MANUAL and RECEIPT_SCAN may be "
				+ "declared by a client — the other values belong to routes that write the row "
				+ "themselves, and naming one here is rejected with 400. Omitted means MANUAL. "
				+ "Ignored on update: a source is recorded once, at creation.",
				allowableValues = {"MANUAL", "RECEIPT_SCAN"})
		String source
) {

	public ExpenseRequest toV1() {
		return new ExpenseRequest(Money.toDecimal(amount), category, date, description,
				expenseType, reimbursable, currency, exchangeRate, source);
	}
}
