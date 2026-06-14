package com.teng.app.gastosai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ChatResponse(
		String type,
		String message,
		@JsonInclude(JsonInclude.Include.NON_NULL) Object result
) {
}
