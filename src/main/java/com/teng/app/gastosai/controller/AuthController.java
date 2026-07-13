package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.config.ClientIps;
import com.teng.app.gastosai.dto.AuthResponse;
import com.teng.app.gastosai.dto.GoogleAuthRequest;
import com.teng.app.gastosai.dto.LoginRequest;
import com.teng.app.gastosai.dto.MagicLinkRequest;
import com.teng.app.gastosai.dto.MagicLinkVerifyRequest;
import com.teng.app.gastosai.dto.RegisterRequest;
import com.teng.app.gastosai.service.AuthService;
import com.teng.app.gastosai.service.GoogleAuthService;
import com.teng.app.gastosai.service.MagicLinkService;
import com.teng.app.gastosai.service.RegistrationGuardService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;
	private final MagicLinkService magicLinkService;
	private final GoogleAuthService googleAuthService;
	private final RegistrationGuardService registrationGuardService;

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
		registrationGuardService.assertRegistrationAllowed(ClientIps.extract(httpRequest));
		return authService.register(request);
	}

	@PostMapping("/login")
	public AuthResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

	@PostMapping("/magic-link")
	public Map<String, Boolean> requestMagicLink(@Valid @RequestBody MagicLinkRequest request, HttpServletRequest httpRequest) {
		magicLinkService.requestLink(request.email(), ClientIps.extract(httpRequest));
		return Map.of("sent", true);
	}

	@PostMapping("/magic-link/verify")
	public AuthResponse verifyMagicLink(@Valid @RequestBody MagicLinkVerifyRequest request) {
		return magicLinkService.verify(request.token());
	}

	@PostMapping("/google")
	public AuthResponse google(@Valid @RequestBody GoogleAuthRequest request, HttpServletRequest httpRequest) {
		return googleAuthService.loginWithIdToken(request.idToken(), ClientIps.extract(httpRequest));
	}
}
