package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.SubmissionType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmissionRequest(
		@NotNull SubmissionType type,
		@Size(max = 100) String name,
		@Email @Size(max = 200) String email,
		@NotBlank @Size(max = 2000) String message
) {
}
