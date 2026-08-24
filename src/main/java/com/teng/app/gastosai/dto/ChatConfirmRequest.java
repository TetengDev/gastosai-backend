package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * The body of {@code POST /ai/chat/confirm}: the action the server proposed, handed straight back.
 *
 * <p>A {@code "preview"} turn returns a {@link ChatPreviewData}. Confirming it means posting that
 * payload's {@code toolName} and {@code params} here verbatim — no English is re-sent, so nothing
 * is re-parsed and the executed action is exactly the one the user saw.
 *
 * @see ChatPreviewData
 */
@Schema(description = "A confirmation of a previously previewed action. Echo the preview's "
		+ "`toolName` and `params` back unchanged.")
public record ChatConfirmRequest(
		@Schema(description = "The tool to run, copied from the preview's `toolName`. An unknown "
				+ "tool is rejected with 400 rather than answered as prose.",
				example = "create_budget",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank @Size(max = 64) String toolName,

		@Schema(description = "The tool's arguments, copied from the preview's `params` unchanged. "
				+ "The key set varies by tool and is not part of this contract.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull Map<String, Object> params,

		@Schema(description = "The conversation the confirmed turn belongs to, so it is recorded in "
				+ "the same transcript as the preview. Omit to start a new conversation.",
				example = "42")
		Long conversationId,

		@Schema(description = "`force` re-runs a create that was held back by the duplicate check "
				+ "(the user chose \"add anyway\"). Anything else, including omitting it, executes "
				+ "with the duplicate check still on.",
				allowableValues = {"execute", "force"},
				example = "execute")
		String mode
) {
}
