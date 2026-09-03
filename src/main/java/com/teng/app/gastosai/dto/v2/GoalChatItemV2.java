package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.GoalChatItem;
import com.teng.app.gastosai.entity.GoalStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/** {@link GoalChatItem} with the money fields as integer centavos. */
@Schema(description = "A savings goal in a chat answer, money in centavos.")
public record GoalChatItemV2(
		@Schema(description = "Stable goal identifier.", example = "7",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long id,

		@Schema(description = "The goal's name.", example = "Emergency fund",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String name,

		@Schema(description = "Target amount in centavos.", example = "5000000",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long targetAmount,

		@Schema(description = "Amount saved so far, in centavos.", example = "1250000",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long savedAmount,

		@Schema(description = "Progress as a percentage, 0–100. Not money: a decimal fraction here is "
				+ "correct.", example = "25.00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal progressPercent,

		@Schema(description = "Where the goal stands against its target date.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		GoalStatus status,

		@Schema(description = "Target date as `YYYY-MM-DD`. Absent — the property is omitted, not "
				+ "null — when the goal has no target date.",
				example = "2026-12-31", format = "date",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		String targetDate
) {
}
