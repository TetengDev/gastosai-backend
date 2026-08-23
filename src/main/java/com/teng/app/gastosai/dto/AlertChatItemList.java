package com.teng.app.gastosai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;

/**
 * The {@code list_alerts} branch of {@link ChatResponse#result()}: a bare JSON array.
 *
 * <p>See {@link GoalChatItemList} for why an array branch needs a named schema of its own.
 * Never constructed.
 */
@JsonIgnoreProperties({"empty", "first", "last"})
@Schema(type = "array", description = "The result of a `list_alerts` turn: a JSON array of alerts.")
public class AlertChatItemList extends ArrayList<AlertChatItem> {
}
