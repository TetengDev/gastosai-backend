package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.dto.UpdateProfileResponse;
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
	private final JwtUtil jwtUtil;

	@GetMapping("/profile")
	public UserProfileResponse getProfile(Authentication authentication) {
		return userProfileService.getProfile(authentication.getName());
	}

	@PutMapping("/profile")
	public UpdateProfileResponse updateProfile(
			Authentication authentication,
			@RequestBody @Valid UserProfileRequest request
	) {
		UserProfileResponse profile = userProfileService.updateProfile(authentication.getName(), request);
		String token = jwtUtil.generate(profile.email());
		return new UpdateProfileResponse(profile.email(), profile.name(), profile.nickname(), profile.avatarColor(), profile.defaultCategory(), profile.avatar(), token);
	}
}
