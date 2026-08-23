package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.AlertSeverity;
import com.teng.app.gastosai.entity.AlertType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One alert as the chat assistant renders it. The {@code result} of a {@code list_alerts} turn is
 * a JSON array of these.
 *
 * <p>Narrower than {@link AlertResponse}: the chat surface drops {@code month},
 * {@code categoryName}, {@code dismissed}, {@code createdAt} and {@code recurringExpenseId} —
 * a dismissed alert is never listed, so the flag would always be false. Documentation only — the
 * wire is unchanged.
 */
@Schema(description = "An alert in a chat answer — enough to render the notice and mark it read.")
public record AlertChatItem(
		@Schema(description = "Stable alert identifier. Pass it back to mark read, dismiss or delete.",
				example = "91",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Long id,

		@Schema(description = "What triggered the alert.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		AlertType type,

		@Schema(description = "How loudly to render it.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		AlertSeverity severity,

		@Schema(description = "The human-readable notice, already composed by the server.",
				example = "You have used 92% of your Groceries budget for August.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String message,

		@Schema(description = "Whether the user has already seen this alert.", example = "false",
				requiredMode = Schema.RequiredMode.REQUIRED)
		boolean read
) {
}
