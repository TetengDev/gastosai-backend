package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.controller.EntitlementController;
import com.teng.app.gastosai.dto.EntitlementResponse;
import com.teng.app.gastosai.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@link EntitlementController} on the v2 path; an entitlement carries no money. */
@RestController
@RequestMapping("/api/v2/user")
@RequiredArgsConstructor
public class EntitlementV2Controller {

	private final EntitlementController delegate;

	@GetMapping("/entitlements")
	@Operation(operationId = "v2Entitlements")
	public EntitlementResponse entitlements(@AuthenticationPrincipal User user) {
		return delegate.entitlements(user);
	}
}
