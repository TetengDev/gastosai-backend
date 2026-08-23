package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The {@code result} of a {@code recategorize_expenses} chat turn.
 *
 * <p>Documentation only; the wire is unchanged.
 */
@Schema(description = "How many expenses a bulk recategorization moved.")
public record RecategorizeChatResult(
		@Schema(description = "The number of expenses moved to the target category. Zero when "
				+ "nothing matched the source category over the requested window.", example = "8",
				requiredMode = Schema.RequiredMode.REQUIRED)
		int updated
) {
}
