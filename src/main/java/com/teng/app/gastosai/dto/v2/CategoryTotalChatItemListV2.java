package com.teng.app.gastosai.dto.v2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;

/**
 * The {@code get_category_totals} branch of {@link ChatResponseV2#result()}: a bare JSON array.
 *
 * <p>See {@link GoalChatItemListV2} for why an array branch needs a named schema of its own.
 * Never constructed.
 */
@JsonIgnoreProperties({"empty", "first", "last"})
@Schema(type = "array", description = "The result of a `get_category_totals` turn: a JSON array of "
		+ "category totals.")
public class CategoryTotalChatItemListV2 extends ArrayList<CategoryTotalChatItemV2> {
}
