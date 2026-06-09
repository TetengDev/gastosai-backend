package com.teng.app.gastosai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiQueryRequest(
		@NotBlank @Size(max = 2000) String question,
		String mode
) {
}
