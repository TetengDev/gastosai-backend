package com.teng.app.gastosai.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ExpenseResponse(
		Long id,
		BigDecimal amount,
		String category,
		@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
		LocalDateTime date,
		String description
) {}
