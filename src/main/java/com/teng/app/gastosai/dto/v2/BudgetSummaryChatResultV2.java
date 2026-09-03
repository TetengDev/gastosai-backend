package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.BudgetSummaryChatResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** {@link BudgetSummaryChatResult} with the money fields as integer centavos. */
@Schema(description = "A month's budget summary as returned by a chat turn, money in centavos.")
public record BudgetSummaryChatResultV2(
		@Schema(description = "The month summarised, `YYYY-MM` in Asia/Manila.", example = "2026-08",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String month,

		@Schema(description = "Sum of every budget limit for the month, in centavos.",
				example = "2500000",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long totalBudgeted,

		@Schema(description = "Sum of spending against those budgets, in centavos.",
				example = "1874025",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long totalSpent,

		@Schema(description = "What remains across all budgets for the month, in centavos.",
				example = "625975",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long safeToSpend,

		@Schema(description = "One line per budgeted category. Empty when no budget is set.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		List<BudgetChatItemV2> items
) {
}
