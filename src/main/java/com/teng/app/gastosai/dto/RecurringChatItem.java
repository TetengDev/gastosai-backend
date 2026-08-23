package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.Frequency;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * One recurring expense inside {@link RecurringChatResult}.
 *
 * <p>Narrower than {@link RecurringExpenseResponse}: the chat surface drops the day-of-period
 * fields, {@code currency} and {@code exchangeRate}. Documentation only — the wire is unchanged.
 */
@Schema(description = "A recurring expense in a chat answer.")
public record RecurringChatItem(
		@Schema(description = "Stable recurring-expense identifier.", example = "13",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long id,

		@Schema(description = "The recurring expense's name.", example = "Netflix",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String name,

		@Schema(description = "Amount charged each period, in PHP.", example = "549.00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal amount,

		@Schema(description = "Category the generated expenses land in.", example = "Subscriptions",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String categoryName,

		@Schema(description = "How often it recurs.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Frequency frequency,

		@Schema(description = "False once paused; a paused entry is still listed, but generates "
				+ "nothing and never appears in `upcoming`.", example = "true",
				requiredMode = Schema.RequiredMode.REQUIRED)
		boolean active
) {
}
