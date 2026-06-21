package com.teng.app.gastosai.dto;

import java.time.LocalDateTime;

public record ConversationSummaryDto(
		Long id,
		String title,
		LocalDateTime createdAt,
		LocalDateTime updatedAt
) {
}
