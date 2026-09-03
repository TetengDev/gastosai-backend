package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.RecurringChatItem;
import com.teng.app.gastosai.entity.Frequency;
import io.swagger.v3.oas.annotations.media.Schema;

/** {@link RecurringChatItem} with {@code amount} as integer centavos. */
@Schema(description = "A recurring expense in a chat answer, money in centavos.")
public record RecurringChatItemV2(
		@Schema(description = "Stable recurring-expense identifier.", example = "13",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long id,

		@Schema(description = "The recurring expense's name.", example = "Netflix",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String name,

		@Schema(description = "Amount charged each period, in centavos.", example = "54900",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long amount,

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
