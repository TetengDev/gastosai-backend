package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.GoalStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * One savings goal as the chat assistant renders it. The {@code result} of a {@code list_goals}
 * turn is a JSON array of these.
 *
 * <p>Deliberately narrower than {@link GoalResponse}: the chat surface omits {@code paused},
 * {@code createdAt} and {@code currency}. Documentation only — the wire is unchanged.
 */
@Schema(description = "A savings goal in a chat answer — the subset a chat bubble renders.")
public record GoalChatItem(
		@Schema(description = "Stable goal identifier.", example = "7",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long id,

		@Schema(description = "The goal's name.", example = "Emergency fund",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String name,

		@Schema(description = "Target amount in PHP, at full decimal precision.", example = "50000.00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal targetAmount,

		@Schema(description = "Amount saved so far, in PHP.", example = "12500.00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal savedAmount,

		@Schema(description = "Progress as a percentage, 0–100. Not money: a decimal fraction here is "
				+ "correct.", example = "25.00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal progressPercent,

		@Schema(description = "Where the goal stands against its target date.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		GoalStatus status,

		@Schema(description = "Target date as `YYYY-MM-DD`. Absent — the property is omitted, not "
				+ "null — when the goal has no target date.",
				example = "2026-12-31", format = "date", nullable = true)
		String targetDate
) {
}
