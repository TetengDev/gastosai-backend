package com.teng.app.gastosai.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MagicLinkRequest(@Email @NotBlank @Size(max = 200) String email) {
}
