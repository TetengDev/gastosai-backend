package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * One category's budget line inside {@link BudgetSummaryChatResult}.
 *
 * <p>Narrower than {@link BudgetSummaryItem}: the chat surface drops {@code categoryId}, because a
 * chat bubble addresses a category by name. Documentation only — the wire is unchanged.
 */
@Schema(description = "One category's budget line in a chat budget summary.")
public record BudgetChatItem(
		@Schema(description = "The budgeted category's name.", example = "Groceries",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String categoryName,

		@Schema(description = "The budget limit for the month, in PHP.", example = "8000.00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal budgeted,

		@Schema(description = "Spent against this budget so far, in PHP.", example = "6420.50",
				requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal spent,

		@Schema(description = "`budgeted` minus `spent`, in PHP. Negative once the budget is exceeded.",
				example = "1579.50",
				requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal remaining,

		@Schema(description = "Share of the budget used, as a percentage. May exceed 100.",
				example = "80.26",
				requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal percentUsed,

		@Schema(description = "Banding derived from `percentUsed`: `WARNING` at 80%, `OVER_BUDGET` at "
				+ "100%. The server computes it — a client must never re-derive it.",
				allowableValues = {"ON_TRACK", "WARNING", "OVER_BUDGET"},
				example = "WARNING",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String status
) {
}
