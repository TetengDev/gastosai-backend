package com.teng.app.gastosai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One assistant turn from {@code POST /ai/chat}.
 *
 * <p>{@code result} stays {@code Object}: {@code ChatActionService} builds each payload as a map or
 * list, and typing it would be a behaviour change on a wire that must not move. What the schema
 * below adds is a <em>description</em> of what that object can be — the shapes were always served,
 * they were simply never published, so every client hand-wrote them (TEN-275).
 *
 * <p>The {@code oneOf} is the payloads specific to the chat surface. Turns that echo a
 * created or updated row return the ordinary resource DTO instead ({@link ExpenseResponse},
 * {@link BudgetResponse}, {@link GoalResponse}, {@link CategoryResponse},
 * {@link UserProfileResponse}), which the contract already publishes under those names.
 */
@Schema(description = "One assistant turn: what kind of turn it is, what to say, and an optional "
		+ "structured payload to render.")
public record ChatResponse(
		@Schema(description = "The turn's kind, and the only field a client should branch on before "
				+ "reading `result`. `text` — prose only, no payload. `action` — something was read "
				+ "or written; `result` carries it. `preview` — a write is pending confirmation and "
				+ "`result` is a ChatPreviewData. `disambiguate` — the assistant needs the user to "
				+ "choose before it proceeds.",
				allowableValues = {"text", "action", "preview", "disambiguate"},
				example = "action",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String type,

		@Schema(description = "The assistant's reply, ready to render. Always present, and always "
				+ "sufficient on its own — a client that cannot render `result` can show this.",
				example = "You have 3 savings goal(s).",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String message,

		@Schema(description = "The structured payload for this turn, omitted when the turn carries "
				+ "none. Which member applies follows from the tool the assistant ran, which is not "
				+ "itself on the wire — so narrow on `type` first, then on the payload's own "
				+ "properties. See TEN-275 for why an explicit per-payload discriminator is not "
				+ "published here.",
				oneOf = {
						ChatPreviewData.class,
						BudgetSummaryChatResult.class,
						RecurringChatResult.class,
						MonthlyReportChatResult.class,
						SubscriptionChatResult.class,
						BulkDeleteChatResult.class,
						RecategorizeChatResult.class,
						GoalChatItemList.class,
						AlertChatItemList.class,
						ExpenseChatItemList.class,
						CategoryTotalChatItemList.class,
						ExpenseDisambiguateItemList.class,
						CategoryResponseList.class
				})
		@JsonInclude(JsonInclude.Include.NON_NULL) Object result,

		@Schema(description = "The conversation this turn belongs to. Omitted — not null — on turns "
				+ "served before a conversation is established, such as the circuit-breaker fallback.",
				example = "42")
		@JsonInclude(JsonInclude.Include.NON_NULL) Long conversationId
) {

	/** Convenience constructor for handlers that don't set a conversation id (set later via {@link #withConversation}). */
	public ChatResponse(String type, String message, Object result) {
		this(type, message, result, null);
	}

	/** Returns a copy tagged with the conversation this response belongs to. */
	public ChatResponse withConversation(Long id) {
		return new ChatResponse(type, message, result, id);
	}
}
