package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * One bill due in the requested month, inside {@link RecurringChatResult}.
 *
 * <p>Much narrower than {@link UpcomingBillResponse}: a chat bubble shows only what is due, when,
 * and for how much. Note there is no {@code id} — an upcoming bill is a projection of a recurring
 * expense, not a row, so it is not addressable. Documentation only — the wire is unchanged.
 */
@Schema(description = "A bill projected to fall due in the requested month.")
public record UpcomingBillChatItem(
		@Schema(description = "The originating recurring expense's name.", example = "Netflix",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String name,

		@Schema(description = "Amount due, in PHP.", example = "549.00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal amount,

		@Schema(description = "The due date as `YYYY-MM-DD`, in Asia/Manila.", example = "2026-08-28",
				format = "date",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String dueDate
) {
}
