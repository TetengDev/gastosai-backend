package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * One row of a user's chat history list.
 *
 * <p>The shape was always on the wire; what was missing is a described one. A client generating
 * from the contract could see four untyped-looking properties and no statement of which of them
 * it may rely on — so {@code title}, the only nullable field here, looked identical to {@code id},
 * which never is. The annotations below are the published answer to that: they carry no runtime
 * behaviour and change no JSON.
 *
 * <p>The schema name stays {@code ConversationSummaryDto}: it is already published under that key
 * and renaming it would break every generated client for cosmetics.
 */
@Schema(description = "A chat conversation as it appears in a history list — enough to render a "
		+ "row and fetch the transcript, without the messages themselves.")
public record ConversationSummaryDto(
		@Schema(description = "Stable identifier. Pass it to GET /chat/conversations/{id} for the transcript.",
				example = "42",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long id,

		@Schema(description = "The conversation's first user message, trimmed to 60 characters. "
				+ "Null until the first turn is recorded — a conversation can exist with no messages, "
				+ "so a history list must render a placeholder rather than assume a label.",
				example = "How much did I spend on groceries?",
				nullable = true)
		String title,

		@Schema(description = "When the conversation was started. ISO-8601 local date-time, no offset.",
				example = "2026-08-19T09:41:12",
				requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDateTime createdAt,

		@Schema(description = "When the last turn was recorded. The list is ordered by this field, "
				+ "newest first, so it is also the sort key a client should display.",
				example = "2026-08-19T10:02:57",
				requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDateTime updatedAt
) {
}
