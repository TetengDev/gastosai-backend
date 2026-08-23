package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * The {@code result} of a {@code list_budgets} chat turn.
 *
 * <p>Narrower than {@link BudgetSummaryResponse}: the chat surface drops {@code dailyAllowance}.
 * Documentation only — the wire is unchanged.
 */
@Schema(description = "A month's budget summary as returned by a chat turn.")
public record BudgetSummaryChatResult(
		@Schema(description = "The month summarised, `YYYY-MM` in Asia/Manila.", example = "2026-08",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String month,

		@Schema(description = "Sum of every budget limit for the month, in PHP.", example = "25000.00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal totalBudgeted,

		@Schema(description = "Sum of spending against those budgets, in PHP.", example = "18740.25",
				requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal totalSpent,

		@Schema(description = "What remains across all budgets for the month, in PHP.",
				example = "6259.75",
				requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal safeToSpend,

		@Schema(description = "One line per budgeted category. Empty when no budget is set.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		List<BudgetChatItem> items
) {
}
