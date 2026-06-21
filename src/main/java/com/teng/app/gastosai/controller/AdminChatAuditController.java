package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.ChatAuditLogDto;
import com.teng.app.gastosai.service.ChatAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin-only read view of the chatbot audit trail. Access enforced in SecurityConfig (`/admin/**`). */
@RestController
@RequestMapping("/admin/chat-audit")
@RequiredArgsConstructor
public class AdminChatAuditController {

	private final ChatAuditService chatAuditService;

	@GetMapping
	public List<ChatAuditLogDto> recent(@RequestParam(defaultValue = "100") int limit) {
		return chatAuditService.recent(limit);
	}
}
