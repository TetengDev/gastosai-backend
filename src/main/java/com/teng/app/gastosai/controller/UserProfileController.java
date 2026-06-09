package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.UserProfileRequest;
import com.teng.app.gastosai.dto.UserProfileResponse;
import com.teng.app.gastosai.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserProfileController {

	private final UserProfileService userProfileService;

	@GetMapping("/profile")
	public UserProfileResponse getProfile(Authentication authentication) {
		return userProfileService.getProfile(authentication.getName());
	}

	@PutMapping("/profile")
	public UserProfileResponse updateProfile(
			Authentication authentication,
			@RequestBody @Valid UserProfileRequest request
	) {
		return userProfileService.updateProfile(authentication.getName(), request);
	}
}
