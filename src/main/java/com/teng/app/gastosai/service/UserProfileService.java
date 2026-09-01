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

import java.util.Optional;

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

	/**
	 * The fields a partial profile update may name. An empty {@link Optional} means "not named by
	 * the caller, leave the stored value alone"; a present one carries the new value and is
	 * normalised exactly as {@link #updateProfile} normalises the same field.
	 *
	 * <p>Deliberately narrower than {@link UserProfileRequest}: {@code email} is not patchable
	 * (changing it needs the uniqueness check and belongs on the full update), and
	 * {@code avatarColor} is not something any caller of this method names.
	 */
	public record ProfilePatch(
			Optional<String> name,
			Optional<String> nickname,
			Optional<String> defaultCategoryName,
			Optional<String> avatar) {
	}

	/**
	 * Writes only the fields the caller actually named, re-reading the user inside the write
	 * transaction rather than restating a caller-held snapshot (TEN-325).
	 *
	 * <p>{@link #updateProfile} is a whole-object PUT: it overwrites every field from the request.
	 * That is correct for the REST endpoint, whose client submits a form it has just rendered, and
	 * wrong for the chat path, where the {@code User} the fields would be restated from is the
	 * detached principal {@code JwtAuthFilter} loaded before the LLM round-trip. Reverting a
	 * nickname the user changed from another device mid-request is the failure that motivated
	 * this method; not writing the field at all is what prevents it, and the re-read is what keeps
	 * the returned response honest.
	 */
	@Transactional
	public UserProfileResponse patchProfile(String currentEmail, ProfilePatch patch) {
		User user = findUser(currentEmail);

		patch.name().ifPresent(user::setName);
		patch.nickname().ifPresent(value -> user.setNickname(value.strip()));
		patch.defaultCategoryName().ifPresent(value ->
				user.setDefaultCategoryName(value.isBlank() ? null : value.strip()));
		patch.avatar().ifPresent(value -> user.setAvatar(value.isBlank() ? null : value.strip()));

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
