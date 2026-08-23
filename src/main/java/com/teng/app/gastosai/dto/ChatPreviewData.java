package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * The {@code result} of a {@code "preview"} chat turn: the action the assistant is about to take,
 * handed back to the client so a human can confirm it before anything is written.
 *
 * <p>This shape has always been on the wire; what was missing is a described one. Documentation
 * only — {@code ChatActionService} builds the payload as a map, so nothing here changes the JSON.
 *
 * @see ChatResponse
 */
@Schema(description = "A pending assistant action awaiting confirmation. Echo `params` back on the "
		+ "confirming turn; the client never interprets them, it only renders and returns them.")
public record ChatPreviewData(
		@Schema(description = "The tool the assistant resolved, e.g. `create_expense`. This is the "
				+ "one field that says which action is pending.",
				example = "create_expense",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String toolName,

		@Schema(description = "The tool's arguments, exactly as the assistant produced them. The key "
				+ "set varies by tool and is not part of this contract — treat it as opaque.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Map<String, Object> params,

		@Schema(description = "The id of the expense this one looks like a duplicate of. Present "
				+ "only on the duplicate-confirmation turn, which arrives as `type: \"disambiguate\"` "
				+ "rather than `\"preview\"`; the property is omitted, not null, on every other turn. "
				+ "A client may show it to let the user open the existing row before confirming.",
				example = "1204",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Long existingId
) {
}
