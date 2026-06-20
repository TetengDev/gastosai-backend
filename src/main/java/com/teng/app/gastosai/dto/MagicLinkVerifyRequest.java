package com.teng.app.gastosai.dto;

import jakarta.validation.constraints.NotBlank;

public record MagicLinkVerifyRequest(@NotBlank String token) {
}
