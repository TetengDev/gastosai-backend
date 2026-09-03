package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.BudgetChatItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * {@link BudgetChatItem} with the money fields as integer centavos.
 *
 * <p>{@code percentUsed} stays a decimal, for the reason {@link BudgetSummaryItemV2} gives: a
 * proportion is not an amount.
 */
@Schema(description = "One category's budget line in a chat budget summary, money in centavos.")
public record BudgetChatItemV2(
		@Schema(description = "The budgeted category's name.", example = "Groceries",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String categoryName,

		@Schema(description = "The budget limit for the month, in centavos.", example = "800000",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long budgeted,

		@Schema(description = "Spent against this budget so far, in centavos.", example = "642050",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long spent,

		@Schema(description = "`budgeted` minus `spent`, in centavos. Negative once the budget is "
				+ "exceeded.", example = "157950",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long remaining,

		@Schema(description = "Share of the budget used, as a percentage. May exceed 100. Not money, "
				+ "so it stays a decimal.", example = "80.26",
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
