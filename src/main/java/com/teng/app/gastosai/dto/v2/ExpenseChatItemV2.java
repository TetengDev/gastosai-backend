package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.ExpenseChatItem;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@link ExpenseChatItem} with {@code amount} as integer centavos.
 *
 * <p>The item of a {@code search_expenses} turn, and of
 * {@link MonthlyReportChatResultV2#topExpenses()}.
 */
@Schema(description = "An expense in a chat answer — flattened for a chat bubble, money in centavos.")
public record ExpenseChatItemV2(
		@Schema(description = "Stable expense identifier.", example = "1204",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long id,

		@Schema(description = "Amount in centavos. The v1 field is a decimal rounded to two places, "
				+ "so this is that value with the point moved right — 320.00 becomes 32000.",
				example = "32000",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long amount,

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
