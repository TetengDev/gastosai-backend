package com.teng.app.gastosai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;

/**
 * The {@code list_goals} branch of {@link ChatResponse#result()}: a bare JSON array.
 *
 * <p>Four of the chat result branches are arrays rather than objects, and a {@code oneOf} member
 * has to be a schema — so each needs a <em>named</em> array schema to point at. A list subclass is
 * how springdoc is given one; the alternative, listing {@link GoalChatItem} directly in the
 * {@code oneOf}, would publish a contract claiming the branch is a single object, which is wrong.
 *
 * <p>Never constructed: it exists to shape the spec, not to carry data.
 */
@JsonIgnoreProperties({"empty", "first", "last"})
@Schema(type = "array", description = "The result of a `list_goals` turn: a JSON array of savings goals.")
public class GoalChatItemList extends ArrayList<GoalChatItem> {
}
