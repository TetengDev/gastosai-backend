package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.controller.UserProfileController;
import com.teng.app.gastosai.dto.UpdateProfileResponse;
import com.teng.app.gastosai.dto.UserProfileRequest;
import com.teng.app.gastosai.dto.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@link UserProfileController} on the v2 path; a profile carries no money. */
@RestController
@RequestMapping("/api/v2/user")
@RequiredArgsConstructor
public class UserProfileV2Controller {

	private final UserProfileController delegate;

	@GetMapping("/profile")
	@Operation(operationId = "v2GetProfile")
	public UserProfileResponse getProfile(Authentication authentication) {
		return delegate.getProfile(authentication);
	}

	@PutMapping("/profile")
	@Operation(operationId = "v2UpdateProfile")
	public UpdateProfileResponse updateProfile(Authentication authentication,
			@RequestBody @Valid UserProfileRequest request) {
		return delegate.updateProfile(authentication, request);
	}
}
