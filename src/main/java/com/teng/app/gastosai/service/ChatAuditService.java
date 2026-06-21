package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.ChatAuditLogDto;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.entity.ChatAuditLog;
import com.teng.app.gastosai.repository.ChatAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Writes the chatbot tool-invocation audit trail. Best-effort: a logging failure never breaks a chat reply. */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatAuditService {

	private static final int MAX_DETAIL = 200;
	private static final int MAX_LIST = 200;

	private final ChatAuditLogRepository repository;

	/** Admin read: the most recent audit entries (capped). */
	@Transactional(readOnly = true)
	public List<ChatAuditLogDto> recent(int limit) {
		int safe = (limit <= 0) ? 100 : Math.min(limit, MAX_LIST);
		return repository.findByOrderByCreatedAtDesc(PageRequest.of(0, safe)).stream()
				.map(a -> new ChatAuditLogDto(a.getId(), a.getUserId(), a.getConversationId(),
						a.getToolName(), a.getStatus().name(), a.getDetail(), a.getCreatedAt()))
				.toList();
	}

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
