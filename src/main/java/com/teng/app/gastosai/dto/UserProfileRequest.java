package com.teng.app.gastosai.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserProfileRequest(
		@NotBlank @Size(max = 100) String name,
		@Size(max = 50) String nickname,
		@NotBlank @Email @Size(max = 200) String email,
		@Size(max = 20) String avatarColor,
		@Size(max = 50) String defaultCategory
) {
}
