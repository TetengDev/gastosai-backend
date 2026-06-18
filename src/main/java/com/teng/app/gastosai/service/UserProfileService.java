package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.UserProfileRequest;
import com.teng.app.gastosai.dto.UserProfileResponse;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class UserProfileService {

	private final UserRepository userRepository;

	@Transactional(readOnly = true)
	public UserProfileResponse getProfile(String email) {
		User user = findUser(email);
		return toResponse(user);
	}

	@Transactional
	public UserProfileResponse updateProfile(String currentEmail, UserProfileRequest request) {
		User user = findUser(currentEmail);

		String newEmail = request.email().strip();
		if (!newEmail.equalsIgnoreCase(user.getEmail())) {
			if (userRepository.existsByEmail(newEmail)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
			}
			user.setEmail(newEmail);
		}

		user.setName(request.name());
		user.setNickname(request.nickname() != null ? request.nickname().strip() : null);
		user.setAvatarColor(request.avatarColor() != null ? request.avatarColor().strip() : null);
		user.setDefaultCategoryName(request.defaultCategory() != null && !request.defaultCategory().isBlank()
				? request.defaultCategory().strip() : null);
		user.setAvatar(request.avatar() != null && !request.avatar().isBlank()
				? request.avatar().strip() : null);

		return toResponse(userRepository.save(user));
	}

	private User findUser(String email) {
		return userRepository.findByEmail(email)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
	}

	private UserProfileResponse toResponse(User user) {
		return new UserProfileResponse(user.getEmail(), user.getName(), user.getNickname(), user.getAvatarColor(), user.getDefaultCategoryName(), user.getAvatar());
	}
}
