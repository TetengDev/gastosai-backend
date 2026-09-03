package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.UpcomingBillChatItem;
import io.swagger.v3.oas.annotations.media.Schema;

/** {@link UpcomingBillChatItem} with {@code amount} as integer centavos. */
@Schema(description = "A bill projected to fall due in the requested month, money in centavos.")
public record UpcomingBillChatItemV2(
		@Schema(description = "The originating recurring expense's name.", example = "Netflix",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String name,

		@Schema(description = "Amount due, in centavos.", example = "54900",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long amount,

		@Schema(description = "The due date as `YYYY-MM-DD`, in Asia/Manila.", example = "2026-08-28",
				format = "date",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String dueDate
) {
}
