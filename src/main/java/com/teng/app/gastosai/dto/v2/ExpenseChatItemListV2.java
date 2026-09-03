package com.teng.app.gastosai.dto.v2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;

/**
 * The {@code search_expenses} branch of {@link ChatResponseV2#result()}: a bare JSON array, capped
 * server-side at 50 items.
 *
 * <p>See {@link GoalChatItemListV2} for why an array branch needs a named schema of its own.
 * Never constructed.
 */
@JsonIgnoreProperties({"empty", "first", "last"})
@Schema(type = "array", description = "The result of a `search_expenses` turn: a JSON array of "
		+ "matching expenses, at most 50.")
public class ExpenseChatItemListV2 extends ArrayList<ExpenseChatItemV2> {
}
