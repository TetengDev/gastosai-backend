package com.teng.app.gastosai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;

/**
 * The {@code list_categories} branch of {@link ChatResponse#result()}: a bare JSON array.
 *
 * <p>The item type is {@link CategoryResponse} itself, not a chat-specific narrowing — this is the
 * one listing turn that returns the resource DTO verbatim rather than hand-building a smaller map,
 * so introducing a {@code CategoryChatItem} would describe a narrower shape than the wire carries.
 *
 * <p>See {@link GoalChatItemList} for why an array branch needs a named schema of its own.
 * Never constructed.
 */
@JsonIgnoreProperties({"empty", "first", "last"})
@Schema(type = "array", description = "The result of a `list_categories` turn: a JSON array of the "
		+ "user's categories, as full CategoryResponse records.")
public class CategoryResponseList extends ArrayList<CategoryResponse> {
}
