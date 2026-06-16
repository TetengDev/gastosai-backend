package com.teng.app.gastosai.service;

import com.teng.app.gastosai.config.AesGcmEncryptor;
import com.teng.app.gastosai.dto.AiSettingsRequest;
import com.teng.app.gastosai.dto.AiSettingsResponse;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class UserAiSettingsService {

	private final UserRepository userRepository;
	private final AesGcmEncryptor encryptor;

	@Transactional(readOnly = true)
	public AiSettingsResponse get(String email) {
		return toResponse(findUser(email));
	}

	@Transactional
	public AiSettingsResponse update(String email, AiSettingsRequest request) {
		User user = findUser(email);
		if (request.openaiApiKey() != null && !request.openaiApiKey().isBlank()) {
			user.setOpenaiApiKeyEnc(encryptor.encrypt(request.openaiApiKey().strip()));
		}
		if (request.claudeApiKey() != null && !request.claudeApiKey().isBlank()) {
			user.setClaudeApiKeyEnc(encryptor.encrypt(request.claudeApiKey().strip()));
		}
		return toResponse(userRepository.save(user));
	}

	@Transactional
	public AiSettingsResponse clear(String email, String provider) {
		User user = findUser(email);
		switch (provider.toLowerCase()) {
			case "openai" -> user.setOpenaiApiKeyEnc(null);
			case "claude" -> user.setClaudeApiKeyEnc(null);
			default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown provider: " + provider);
		}
		return toResponse(userRepository.save(user));
	}

	private User findUser(String email) {
		return userRepository.findByEmail(email)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
	}

	private AiSettingsResponse toResponse(User user) {
		return new AiSettingsResponse(
				user.getOpenaiApiKeyEnc() != null && !user.getOpenaiApiKeyEnc().isBlank(),
				user.getClaudeApiKeyEnc() != null && !user.getClaudeApiKeyEnc().isBlank());
	}
}
