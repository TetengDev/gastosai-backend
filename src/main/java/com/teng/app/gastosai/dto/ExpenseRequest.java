package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.ExpenseSource;
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
		@Schema(description = SOURCE_DESCRIPTION,
				allowableValues = {DECLARABLE_MANUAL, DECLARABLE_RECEIPT_SCAN})
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
	 *
	 * The narrowing lives in the constants below rather than in the annotation, because
	 * ExpenseRequestV2 publishes the same field and a second literal list there would be a second
	 * thing to keep in step with ExpenseSource by hand. ExpenseRequestV2Test asserts the pair
	 * still equals ExpenseSource.isClientDeclarable(), which is the check a literal cannot make.
	 */

	/** The one name for {@link ExpenseSource#MANUAL} that an annotation can hold. */
	public static final String DECLARABLE_MANUAL = "MANUAL";

	/** The one name for {@link ExpenseSource#RECEIPT_SCAN} that an annotation can hold. */
	public static final String DECLARABLE_RECEIPT_SCAN = "RECEIPT_SCAN";

	/** What {@code source} means, published identically by v1 and v2. */
	public static final String SOURCE_DESCRIPTION =
			"How this expense was created. Only MANUAL and RECEIPT_SCAN may be "
					+ "declared by a client — the other values belong to routes that write the row "
					+ "themselves, and naming one here is rejected with 400. Omitted means MANUAL. "
					+ "Ignored on update: a source is recorded once, at creation.";

	/**
	 * The pre-{@code source} arity, kept so the callers that have no source to declare — the chat
	 * assistant, the quick-add path — read the same as before. They go through
	 * {@code ExpenseService.create(request, user, source)}, which names the source explicitly.
	 */
	public ExpenseRequest(BigDecimal amount, String category, LocalDateTime date, String description,
			String expenseType, Boolean reimbursable, String currency, BigDecimal exchangeRate) {
		this(amount, category, date, description, expenseType, reimbursable, currency, exchangeRate, null, null);
	}
}
