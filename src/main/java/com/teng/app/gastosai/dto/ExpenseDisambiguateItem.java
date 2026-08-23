package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One candidate in a {@code "disambiguate"} turn: the user asked to delete an expense by
 * description and more than one matched, so the assistant is asking which.
 *
 * <p>Deliberately <em>not</em> {@link ExpenseChatItem}. This shape carries no {@code category},
 * and its {@code amount} is unrounded — the raw column value, where {@code ExpenseChatItem}
 * rounds to two places. A client that reused {@code ExpenseChatItem} here would find a required
 * property missing, which is exactly why this needs a schema of its own.
 *
 * <p>Documentation only; the wire is unchanged.
 */
@Schema(description = "One expense the user must choose between before a delete can proceed.")
public record ExpenseDisambiguateItem(
		@Schema(description = "The expense's identifier. Send it back to name the one to delete.",
				example = "1204",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long id,

		@Schema(description = "What the expense was for — the field the user's ambiguous phrase "
				+ "matched.", example = "SM Supermarket",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String description,

		@Schema(description = "Amount in PHP, at the stored precision. Unlike ExpenseChatItem this "
				+ "is not rounded for display, so format it at the edge.", example = "320.0000",
				requiredMode = Schema.RequiredMode.REQUIRED)
		java.math.BigDecimal amount,

		@Schema(description = "The expense date as `YYYY-MM-DD` in Asia/Manila, or an empty string "
				+ "on the rare rows with no date.", example = "2026-08-14",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String date
) {
}
