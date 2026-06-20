package com.teng.app.gastosai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MagicLinkVerifyRequest(@NotBlank @Size(max = 512) String token) {
}
