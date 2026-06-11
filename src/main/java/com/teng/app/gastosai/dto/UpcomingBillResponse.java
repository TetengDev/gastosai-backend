package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.Frequency;

import java.math.BigDecimal;

public record UpcomingBillResponse(
		Long id,
		String name,
		BigDecimal amount,
		String categoryName,
		Frequency frequency,
		String dueDate
) {
}
