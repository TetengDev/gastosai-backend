package com.teng.app.gastosai.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ExpenseResponse(
		Long id,

		BigDecimal amount,

		String category,
		LocalDate date,
		String note
) {}
