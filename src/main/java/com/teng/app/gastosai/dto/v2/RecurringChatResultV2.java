package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.RecurringChatResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** {@link RecurringChatResult} with the money fields as integer centavos. */
@Schema(description = "A user's recurring expenses and the bills due this month, money in centavos.")
public record RecurringChatResultV2(
		@Schema(description = "Every recurring expense the user has, active or paused.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		List<RecurringChatItemV2> items,

		@Schema(description = "Bills falling due in the requested month. A projection of `items`, not "
				+ "a parallel list — the two can differ in length, and a paused entry appears in "
				+ "`items` only.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		List<UpcomingBillChatItemV2> upcoming
) {
}
