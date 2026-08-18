package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.controller.UserAiSettingsController;
import com.teng.app.gastosai.dto.AiSettingsRequest;
import com.teng.app.gastosai.dto.AiSettingsResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@link UserAiSettingsController} on the v2 path; AI settings carry no money. */
@RestController
@RequestMapping("/api/v2/user/ai-settings")
@RequiredArgsConstructor
public class UserAiSettingsV2Controller {

	private final UserAiSettingsController delegate;

	@GetMapping
	@Operation(operationId = "v2GetAiSettings")
	public AiSettingsResponse get(Authentication authentication) {
		return delegate.get(authentication);
	}

	@PutMapping
	@Operation(operationId = "v2UpdateAiSettings")
	public AiSettingsResponse update(Authentication authentication, @RequestBody AiSettingsRequest request) {
		return delegate.update(authentication, request);
	}

	@DeleteMapping("/{provider}")
	@Operation(operationId = "v2ClearAiSettings")
	public AiSettingsResponse clear(Authentication authentication, @PathVariable String provider) {
		return delegate.clear(authentication, provider);
	}
}
