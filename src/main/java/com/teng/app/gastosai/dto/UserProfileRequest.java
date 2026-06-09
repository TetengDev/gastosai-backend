package com.teng.app.gastosai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserProfileRequest(
		@NotBlank @Size(max = 100) String name,
		@Size(max = 50) String nickname
) {
}
