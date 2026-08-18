package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.controller.AdminChatAuditController;
import com.teng.app.gastosai.dto.ChatAuditLogDto;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** {@link AdminChatAuditController} on the v2 path. Access enforced in SecurityConfig (`/admin/**`). */
@RestController
@RequestMapping("/api/v2/admin/chat-audit")
@RequiredArgsConstructor
public class AdminChatAuditV2Controller {

	private final AdminChatAuditController delegate;

	@GetMapping
	@Operation(operationId = "v2AdminChatAudit")
	public List<ChatAuditLogDto> recent(@RequestParam(defaultValue = "100") int limit) {
		return delegate.recent(limit);
	}
}
