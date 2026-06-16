package com.teng.app.gastosai.config;

import com.teng.app.gastosai.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * On /ai/** requests, loads the authenticated user's own (decrypted) AI keys into {@link AiKeyContext}
 * so the outbound LLM calls bill to the user's key when set, falling back to the owner key otherwise.
 * Always cleared in afterCompletion to avoid leaking across pooled threads.
 */
@Component
@RequiredArgsConstructor
public class AiKeyContextInterceptor implements HandlerInterceptor {

	private final AesGcmEncryptor encryptor;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof User user) {
			AiKeyContext.set(decryptSafe(user.getOpenaiApiKeyEnc()), decryptSafe(user.getClaudeApiKeyEnc()));
		}
		return true;
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
		AiKeyContext.clear();
	}

	private String decryptSafe(String stored) {
		if (stored == null || stored.isBlank()) {
			return null;
		}
		try {
			return encryptor.decrypt(stored);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
