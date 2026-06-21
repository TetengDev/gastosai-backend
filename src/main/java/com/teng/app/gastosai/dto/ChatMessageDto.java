package com.teng.app.gastosai.dto;

import java.time.LocalDateTime;

public record ChatMessageDto(
		Long id,
		String role,
		String content,
		String toolName,
		String responseType,
		LocalDateTime createdAt
) {
}
