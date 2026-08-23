package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * One expense as the chat assistant renders it — the {@code result} of a {@code search_expenses}
 * turn is a JSON array of these, and {@link MonthlyReportChatResult#topExpenses()} reuses the
 * shape.
 *
 * <p>Narrower than {@link ExpenseResponse}, and note {@code category} here is a plain name rather
 * than the nested category object. Documentation only — the wire is unchanged.
 */
@Schema(description = "An expense in a chat answer — flattened for a chat bubble.")
public record ExpenseChatItem(
		@Schema(description = "Stable expense identifier.", example = "1204",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long id,

		@Schema(description = "Amount in PHP, rounded to two decimal places for display.",
				example = "320.00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal amount,

		@Schema(description = "Category name, or the literal `Uncategorized` when the expense has no "
				+ "category. Never null.", example = "Groceries",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String category,

		@Schema(description = "The expense date as `YYYY-MM-DD` in Asia/Manila, or an empty string on "
				+ "the rare rows with no date. Not declared as a date format for that reason — parse "
				+ "defensively.", example = "2026-08-14",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String date,

		@Schema(description = "What the expense was for.", example = "SM Supermarket",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String description
) {
}
