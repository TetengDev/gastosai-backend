package com.teng.app.gastosai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ChatResponse(
		String type,
		String message,
		@JsonInclude(JsonInclude.Include.NON_NULL) Object result,
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
