package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * The {@code result} of a {@code get_monthly_report} chat turn.
 *
 * <p>Documentation only — the wire is unchanged.
 */
@Schema(description = "A month's spending report as returned by a chat turn.")
public record MonthlyReportChatResult(
		@Schema(description = "The month reported, `YYYY-MM` in Asia/Manila.", example = "2026-08",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String month,

		@Schema(description = "Total spent in the month, in PHP. Equals the sum of "
				+ "`categoryBreakdown[].total`; the server computes it, a client must not re-derive it.",
				example = "18740.25",
				requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal totalSpent,

		@Schema(description = "Spend per category for the month. Empty when nothing was spent.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		List<CategoryTotalChatItem> categoryBreakdown,

		@Schema(description = "The month's five largest expenses, descending by amount. Fewer than "
				+ "five when the month has fewer expenses.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		List<ExpenseChatItem> topExpenses
) {
}
