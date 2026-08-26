package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
		String source,
		@Size(max = 60)
		@Schema(description = "The project or client this expense is billable to, by name. Created "
				+ "on first use and matched case-insensitively afterwards, like a category. Omit "
				+ "it — or send null or an empty string — for an untagged expense; on update that "
				+ "removes a tag the expense already carried.")
		String project
) {

	/*
	 * `source` is a String, not an ExpenseSource, and only here on the request.
	 *
	 * Binding an unknown name straight onto the enum fails inside Jackson, and the only handler
	 * that would catch it is the catch-all that answers 500 — so "source": "TELEPATHY" would be a
	 * server error rather than the 400 it plainly is. Parsing it in ExpenseService keeps the
	 * refusal a 400 with a message that names the values, and @Schema still publishes the two
	 * allowable names, so a generated client sees the same union it would have seen.
	 */

	/**
	 * The pre-{@code source} arity, kept so the callers that have no source to declare — the chat
	 * assistant, the quick-add path, the v2 request — read the same as before. They go through
	 * {@code ExpenseService.create(request, user, source)}, which names the source explicitly.
	 */
	public ExpenseRequest(BigDecimal amount, String category, LocalDateTime date, String description,
			String expenseType, Boolean reimbursable, String currency, BigDecimal exchangeRate) {
		this(amount, category, date, description, expenseType, reimbursable, currency, exchangeRate, null, null);
	}

	/** The pre-{@code project} arity, kept for the same reason as the one above. */
	public ExpenseRequest(BigDecimal amount, String category, LocalDateTime date, String description,
			String expenseType, Boolean reimbursable, String currency, BigDecimal exchangeRate,
			String source) {
		this(amount, category, date, description, expenseType, reimbursable, currency, exchangeRate, source, null);
	}
}
