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
	public UserProfileResponse updateProfile(String email, UserProfileRequest request) {
		User user = findUser(email);
		user.setName(request.name());
		user.setNickname(request.nickname() != null ? request.nickname().strip() : null);
		return toResponse(userRepository.save(user));
	}

	private User findUser(String email) {
		return userRepository.findByEmail(email)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
	}

	private UserProfileResponse toResponse(User user) {
		return new UserProfileResponse(user.getEmail(), user.getName(), user.getNickname());
	}
}
