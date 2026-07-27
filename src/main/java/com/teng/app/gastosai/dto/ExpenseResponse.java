package com.teng.app.gastosai.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ExpenseResponse(
		Long id,
		BigDecimal amount,
		String category,
		// Serialized with an explicit +08:00 offset by JacksonTimeConfig. The previous
		// @JsonFormat pattern emitted a naive timestamp, which the contract's `date-time`
		// declaration does not permit.
		LocalDateTime date,
		String description,
		String expenseType,
		boolean reimbursable,
		String currency,
		BigDecimal exchangeRate,
		BigDecimal amountInBaseCurrency
) {}
