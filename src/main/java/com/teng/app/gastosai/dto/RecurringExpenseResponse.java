package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.Frequency;

import java.math.BigDecimal;

public record RecurringExpenseResponse(
		Long id,
		String name,
		BigDecimal amount,
		String categoryName,
		Frequency frequency,
		Integer dayOfMonth,
		Integer dayOfWeek,
		Integer monthOfYear,
		boolean active
) {
}
