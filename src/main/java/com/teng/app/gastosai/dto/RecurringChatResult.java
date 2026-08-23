package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The {@code result} of a {@code list_recurring} chat turn: the standing recurring expenses, plus
 * the subset projected to fall due in the requested month.
 *
 * <p>Documentation only — the wire is unchanged.
 */
@Schema(description = "A user's recurring expenses and the bills due this month.")
public record RecurringChatResult(
		@Schema(description = "Every recurring expense the user has, active or paused.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		List<RecurringChatItem> items,

		@Schema(description = "Bills falling due in the requested month. A projection of `items`, not "
				+ "a parallel list — the two can differ in length, and a paused entry appears in "
				+ "`items` only.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		List<UpcomingBillChatItem> upcoming
) {
}
