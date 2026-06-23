package com.teng.app.gastosai.dto;

import jakarta.validation.constraints.NotBlank;

public record GoogleAuthRequest(@NotBlank String idToken) {
}
