package com.teng.app.gastosai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ParseExpenseRequest(
        @NotBlank @Size(max = 500) String text
) {}
