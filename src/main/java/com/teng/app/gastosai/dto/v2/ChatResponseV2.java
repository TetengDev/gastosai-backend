package com.teng.app.gastosai.dto.v2;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.teng.app.gastosai.dto.AlertChatItemList;
import com.teng.app.gastosai.dto.BulkDeleteChatResult;
import com.teng.app.gastosai.dto.CategoryResponseList;
import com.teng.app.gastosai.dto.ChatPreviewData;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.dto.RecategorizeChatResult;
import com.teng.app.gastosai.dto.SubscriptionChatResult;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@link ChatResponse} with the money inside {@code result} as integer centavos (TEN-308).
 *
 * <p>{@code /api/v2/ai/chat} used to return the v1 object verbatim, which made it the one v2
 * endpoint still serving decimal money, and it was described nowhere — {@code ChatResponseV2} did
 * not exist as a schema, so a client generating from the contract got no type for the surface it
 * was told to migrate to. Both halves are fixed here: the payload is restated by
 * {@link ChatResultV2}, and every shape it can carry is published.
 *
 * <p>{@code result} stays {@code Object} for the reason v1 gives — {@code ChatActionService} builds
 * each payload as a map or list, and typing it would be a behaviour change on a wire that must not
 * move.
 *
 * <p>Discriminated the same way v1 is: on {@code type}, the one field a client may branch on
 * before reading {@code result}. There is still no per-payload discriminator, because the tool the
 * assistant ran is not on the wire; narrow on {@code type}, then on the payload's own properties.
 *
 * <p>Members that carry no money are the v1 schemas themselves rather than identical twins — the
 * same choice {@code ConversationV2Controller} makes for a transcript. Turns that echo a created or
 * updated row return the ordinary v2 resource DTO instead ({@link ExpenseResponseV2},
 * {@link BudgetResponseV2}, {@link GoalResponseV2}, {@link RecurringExpenseResponseV2}, or the
 * money-free {@code CategoryResponse} and {@code UserProfileResponse}), which the contract already
 * publishes under those names.
 */
@Schema(description = "One assistant turn: what kind of turn it is, what to say, and an optional "
		+ "structured payload to render. Money inside the payload is an integer number of centavos.")
public record ChatResponseV2(
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
				+ "sufficient on its own — a client that cannot render `result` can show this. "
				+ "Amounts quoted in this prose are formatted pesos, not centavos.",
				example = "You have 3 savings goal(s).",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String message,

		@Schema(description = "The structured payload for this turn, omitted when the turn carries "
				+ "none. Which member applies follows from the tool the assistant ran, which is not "
				+ "itself on the wire — so narrow on `type` first, then on the payload's own "
				+ "properties. The `preview` branch is a ChatPreviewData whose `params` are the v1 "
				+ "decimal arguments, echoed back unchanged to POST /ai/chat/confirm.",
				oneOf = {
						ChatPreviewData.class,
						BudgetSummaryChatResultV2.class,
						RecurringChatResultV2.class,
						MonthlyReportChatResultV2.class,
						SubscriptionChatResult.class,
						BulkDeleteChatResult.class,
						RecategorizeChatResult.class,
						GoalChatItemListV2.class,
						AlertChatItemList.class,
						ExpenseChatItemListV2.class,
						CategoryTotalChatItemListV2.class,
						ExpenseDisambiguateItemListV2.class,
						CategoryResponseList.class
				})
		@JsonInclude(JsonInclude.Include.NON_NULL) Object result,

		@Schema(description = "The conversation this turn belongs to. Omitted — not null — on turns "
				+ "served before a conversation is established, such as the circuit-breaker fallback.",
				example = "42")
		@JsonInclude(JsonInclude.Include.NON_NULL) Long conversationId
) {

	/** The v1 turn with its payload's money restated in centavos. */
	public static ChatResponseV2 from(ChatResponse v1) {
		return v1 == null ? null : new ChatResponseV2(
				v1.type(),
				v1.message(),
				ChatResultV2.from(v1.result()),
				v1.conversationId());
	}
}
