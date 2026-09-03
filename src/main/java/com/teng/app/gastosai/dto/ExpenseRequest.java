package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.ExpenseSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

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
		ExpenseSource source,
		@Size(max = 60)
		@Schema(description = "The project or client this expense is billable to, by name. Created "
				+ "on first use and matched case-insensitively afterwards, like a category. Omit "
				+ "it — or send null or an empty string — for an untagged expense; on update that "
				+ "removes a tag the expense already carried.")
		String project
) {

	/*
	 * `source` is an ExpenseSource. TEN-175 declared it a String because binding an unknown name
	 * onto the enum failed inside Jackson and reached only the catch-all that answered 500 — so
	 * "source": "TELEPATHY" was a server error rather than the 400 it plainly is. TEN-309 handles
	 * HttpMessageNotReadableException and answers 400 naming the field and its values, so the type
	 * is back and the parse ExpenseService used to run by hand is gone.
	 *
	 * @Schema still narrows the published values to the two a client may declare: the enum has
	 * five, and the other three belong to routes that write the row themselves. Naming one of those
	 * is a valid enum name, so it binds — ExpenseService refuses it, as it always did.
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

	/**
	 * The pre-{@code project} arity, and the bridge {@code ExpenseRequestV2.toV1()} crosses.
	 *
	 * <p>It takes the source as a {@code String} because v2's own field is still one. That request
	 * binds its source as text, so an unknown name never reaches the Jackson handler that answers
	 * v1's — it arrives here intact, and is refused with the same 400 v2 has always answered.
	 * Typing v2's field removes this overload along with the parse below.
	 */
	public ExpenseRequest(BigDecimal amount, String category, LocalDateTime date, String description,
			String expenseType, Boolean reimbursable, String currency, BigDecimal exchangeRate,
			String source) {
		this(amount, category, date, description, expenseType, reimbursable, currency, exchangeRate,
				parseSource(source), null);
	}

	private static ExpenseSource parseSource(String declared) {
		if (declared == null || declared.isBlank()) {
			return null;
		}
		try {
			return ExpenseSource.valueOf(declared.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"source must be MANUAL or RECEIPT_SCAN — got '" + declared + "'.");
		}
	}
}
