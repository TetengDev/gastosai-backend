package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.ExpenseRequest;
import com.teng.app.gastosai.entity.ExpenseSource;
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
		@Schema(description = ExpenseRequest.SOURCE_DESCRIPTION,
				allowableValues = {ExpenseRequest.DECLARABLE_MANUAL,
						ExpenseRequest.DECLARABLE_RECEIPT_SCAN})
		ExpenseSource source
) {

	/*
	 * `source` is the same ExpenseSource v1 binds, and publishes the same narrowed values from the
	 * same constants — so an added enum constant cannot leave the v2 schema saying something v1
	 * does not. An unknown name fails inside Jackson on this record exactly as it does on v1, and
	 * the HttpMessageNotReadableException handler answers the 400; a real source that is not the
	 * client's to declare binds, and ExpenseService refuses it. Neither refusal is v2's own work,
	 * which is the point: TEN-312 removed the hand-rolled parse from v1 and TEN-335 removes the
	 * bridge that kept a copy of it alive for v2.
	 */

	public ExpenseRequest toV1() {
		return new ExpenseRequest(Money.toDecimal(amount), category, date, description,
				expenseType, reimbursable, currency, exchangeRate, source, null);
	}
}
