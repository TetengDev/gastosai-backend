package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The {@code result} of a {@code delete_expenses} chat turn.
 *
 * <p>Documentation only; the wire is unchanged.
 */
@Schema(description = "How many expenses a bulk delete actually removed.")
public record BulkDeleteChatResult(
		@Schema(description = "The number of expenses deleted. Can be lower than the number asked "
				+ "for — ids that no longer exist are skipped rather than failing the turn — and can "
				+ "be zero when a filter matched nothing.", example = "12",
				requiredMode = Schema.RequiredMode.REQUIRED)
		int deleted
) {
}
