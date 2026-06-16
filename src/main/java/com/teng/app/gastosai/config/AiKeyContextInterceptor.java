package com.teng.app.gastosai.config;

import com.teng.app.gastosai.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * On /ai/** requests, loads the authenticated user's own (decrypted) AI keys into {@link AiKeyContext}
 * so the outbound LLM calls bill to the user's key. When the active provider's key is missing and
 * shared-key fallback is disabled (default), the request is rejected with 402 so the UI can prompt the
 * user to add their key — the rest of the app keeps working. Context is always cleared afterward.
 */
@Component
public class AiKeyContextInterceptor implements HandlerInterceptor {

	private final AesGcmEncryptor encryptor;
	private final AiProviderProperties providerProperties;
	private final boolean allowSharedKey;

	public AiKeyContextInterceptor(AesGcmEncryptor encryptor, AiProviderProperties providerProperties,
			@Value("${gastos.ai.allow-shared-key:false}") boolean allowSharedKey) {
		this.encryptor = encryptor;
		this.providerProperties = providerProperties;
		this.allowSharedKey = allowSharedKey;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
			return true;
		}

		// Admin "View As: AI off" — simulate the no-key state even if the admin has a key.
		if (Boolean.FALSE.equals(ViewAsContext.aiEnabled())) {
			return reject(response);
		}

		String openaiKey = decryptSafe(user.getOpenaiApiKeyEnc());
		String claudeKey = decryptSafe(user.getClaudeApiKeyEnc());
		AiKeyContext.set(openaiKey, claudeKey);

		if (allowSharedKey || hasActiveProviderKey(openaiKey, claudeKey)) {
			return true;
		}

		AiKeyContext.clear();
		return reject(response);
	}

	private boolean reject(HttpServletResponse response) throws IOException {
		response.setStatus(HttpStatus.PAYMENT_REQUIRED.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write(
				"{\"title\":\"AI key required\",\"detail\":\"Add your OpenAI key in Settings to use AI features.\"}");
		return false;
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
		AiKeyContext.clear();
	}

	private boolean hasActiveProviderKey(String openaiKey, String claudeKey) {
		String activeKey = "claude".equalsIgnoreCase(providerProperties.getProvider()) ? claudeKey : openaiKey;
		return activeKey != null && !activeKey.isBlank();
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
