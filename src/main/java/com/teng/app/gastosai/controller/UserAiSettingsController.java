package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.AiSettingsRequest;
import com.teng.app.gastosai.dto.AiSettingsResponse;
import com.teng.app.gastosai.service.UserAiSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/ai-settings")
@RequiredArgsConstructor
public class UserAiSettingsController {

	private final UserAiSettingsService userAiSettingsService;

	@GetMapping
	public AiSettingsResponse get(Authentication authentication) {
		return userAiSettingsService.get(authentication.getName());
	}

	@PutMapping
	public AiSettingsResponse update(Authentication authentication, @RequestBody AiSettingsRequest request) {
		return userAiSettingsService.update(authentication.getName(), request);
	}

	@DeleteMapping("/{provider}")
	public AiSettingsResponse clear(Authentication authentication, @PathVariable String provider) {
		return userAiSettingsService.clear(authentication.getName(), provider);
	}
}
