package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.controller.AiUsageController;
import com.teng.app.gastosai.dto.AiUsageResponse;
import com.teng.app.gastosai.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@link AiUsageController} on the v2 path; a quota is a request count, not money. */
@RestController
@RequestMapping("/api/v2/ai")
@RequiredArgsConstructor
public class AiUsageV2Controller {

	private final AiUsageController delegate;

	@GetMapping("/usage")
	@Operation(operationId = "v2AiUsage")
	public AiUsageResponse usage(@AuthenticationPrincipal User user) {
		return delegate.usage(user);
	}
}
