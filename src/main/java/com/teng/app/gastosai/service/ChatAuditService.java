package com.teng.app.gastosai.service;

import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.entity.ChatAuditLog;
import com.teng.app.gastosai.repository.ChatAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Writes the chatbot tool-invocation audit trail. Best-effort: a logging failure never breaks a chat reply. */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatAuditService {

	private static final int MAX_DETAIL = 200;

	private final ChatAuditLogRepository repository;

	@Transactional
	public void record(Long userId, Long conversationId, String toolName, AiUsageStatus status, String detail) {
		try {
			repository.save(ChatAuditLog.builder()
					.userId(userId)
					.conversationId(conversationId)
					.toolName(toolName)
					.status(status)
					.detail(truncate(detail))
					.build());
		} catch (Exception e) {
			log.warn("chat_audit_write_failed", e);
		}
	}

	private static String truncate(String s) {
		if (s == null) return null;
		return s.length() <= MAX_DETAIL ? s : s.substring(0, MAX_DETAIL);
	}
}
