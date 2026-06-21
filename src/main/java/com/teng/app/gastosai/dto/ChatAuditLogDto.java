package com.teng.app.gastosai.dto;

import java.time.LocalDateTime;

public record ChatAuditLogDto(
		Long id,
		Long userId,
		Long conversationId,
		String toolName,
		String status,
		String detail,
		LocalDateTime createdAt
) {
}
