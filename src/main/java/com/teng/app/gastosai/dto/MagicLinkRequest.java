package com.teng.app.gastosai.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record MagicLinkRequest(@Email @NotBlank String email) {
}
