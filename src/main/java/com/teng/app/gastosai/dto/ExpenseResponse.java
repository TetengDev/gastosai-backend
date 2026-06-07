package com.teng.app.gastosai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ExpenseResponse(
		@JsonIgnore
		Long id,

		BigDecimal amount,

		String category,
		LocalDate date,
		String note
) {}
