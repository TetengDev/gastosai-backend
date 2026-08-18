package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.controller.AuthController;
import com.teng.app.gastosai.dto.AuthResponse;
import com.teng.app.gastosai.dto.GoogleAuthRequest;
import com.teng.app.gastosai.dto.LoginRequest;
import com.teng.app.gastosai.dto.MagicLinkRequest;
import com.teng.app.gastosai.dto.MagicLinkVerifyRequest;
import com.teng.app.gastosai.dto.RegisterRequest;
import io.swagger.v3.oas.annotations.Operation;
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

/**
 * {@link AuthController} on the v2 path.
 *
 * <p>No money crosses this surface, so the shapes are identical to v1. It exists because a client
 * that repoints its base URL to {@code /api/v2} has to be able to log in there; a version that
 * covered only the money-bearing endpoints would force every client to hold two base URLs.
 */
@RestController
@RequestMapping("/api/v2/auth")
@RequiredArgsConstructor
public class AuthV2Controller {

	private final AuthController delegate;

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(operationId = "v2Register")
	public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
		return delegate.register(request, httpRequest);
	}

	@PostMapping("/login")
	@Operation(operationId = "v2Login")
	public AuthResponse login(@Valid @RequestBody LoginRequest request) {
		return delegate.login(request);
	}

	@PostMapping("/magic-link")
	@Operation(operationId = "v2RequestMagicLink")
	public Map<String, Boolean> requestMagicLink(@Valid @RequestBody MagicLinkRequest request,
			HttpServletRequest httpRequest) {
		return delegate.requestMagicLink(request, httpRequest);
	}

	@PostMapping("/magic-link/verify")
	@Operation(operationId = "v2VerifyMagicLink")
	public AuthResponse verifyMagicLink(@Valid @RequestBody MagicLinkVerifyRequest request) {
		return delegate.verifyMagicLink(request);
	}

	@PostMapping("/google")
	@Operation(operationId = "v2GoogleAuth")
	public AuthResponse google(@Valid @RequestBody GoogleAuthRequest request, HttpServletRequest httpRequest) {
		return delegate.google(request, httpRequest);
	}
}
