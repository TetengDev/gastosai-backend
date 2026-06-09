package com.teng.app.gastosai.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ExpenseResponse(
		Long id,
		BigDecimal amount,
		String category,
		LocalDateTime date,
		String description
) {}
