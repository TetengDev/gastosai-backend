package com.teng.app.gastosai.dto.v2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;

/**
 * The delete-expense disambiguation branch of {@link ChatResponseV2#result()}: a bare JSON array.
 *
 * <p>See {@link GoalChatItemListV2} for why an array branch needs a named schema of its own.
 * Never constructed.
 */
@JsonIgnoreProperties({"empty", "first", "last"})
@Schema(type = "array", description = "The result of a delete-expense `disambiguate` turn: a JSON "
		+ "array of the candidates the user must choose between.")
public class ExpenseDisambiguateItemListV2 extends ArrayList<ExpenseDisambiguateItemV2> {
}
