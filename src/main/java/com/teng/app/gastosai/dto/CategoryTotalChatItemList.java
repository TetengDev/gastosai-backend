package com.teng.app.gastosai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;

/**
 * The {@code get_category_totals} branch of {@link ChatResponse#result()}: a bare JSON array.
 *
 * <p>See {@link GoalChatItemList} for why an array branch needs a named schema of its own.
 * Never constructed.
 */
@JsonIgnoreProperties({"empty", "first", "last"})
@Schema(type = "array", description = "The result of a `get_category_totals` turn: a JSON array of category totals.")
public class CategoryTotalChatItemList extends ArrayList<CategoryTotalChatItem> {
}
