package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.MonthlyReportChatResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** {@link MonthlyReportChatResult} with the money fields as integer centavos. */
@Schema(description = "A month's spending report as returned by a chat turn, money in centavos.")
public record MonthlyReportChatResultV2(
		@Schema(description = "The month reported, `YYYY-MM` in Asia/Manila.", example = "2026-08",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String month,

		@Schema(description = "Total spent in the month, in centavos. Equals the sum of "
				+ "`categoryBreakdown[].total`; the server computes it, a client must not re-derive it.",
				example = "1874025",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long totalSpent,

		@Schema(description = "Spend per category for the month. Empty when nothing was spent.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		List<CategoryTotalChatItemV2> categoryBreakdown,

		@Schema(description = "The month's five largest expenses, descending by amount. Fewer than "
				+ "five when the month has fewer expenses.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		List<ExpenseChatItemV2> topExpenses
) {
}
