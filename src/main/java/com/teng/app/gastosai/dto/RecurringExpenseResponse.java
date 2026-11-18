package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.Frequency;
import io.swagger.v3.oas.annotations.media.Schema;

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
		boolean active,
		String currency,
		BigDecimal exchangeRate,
		@Schema(description = "Whether this bill is marked paid for the current month, decided in "
				+ "Asia/Manila. One mark covers the whole month, so a WEEKLY bill is paid or unpaid "
				+ "for the month as a whole.")
		boolean paidThisMonth,
		@Schema(description = "The latest month this bill was marked paid, `YYYY-MM` in Asia/Manila, "
				+ "or null if it has never been marked paid. Clients use it to show a previous month "
				+ "left unpaid, so an overdue bill stays visible after its month ends.",
				example = "2026-09", nullable = true)
		String lastPaidMonth
) {

	/** The shape before the paid state existed, for callers that never read it: unpaid, never paid. */
	public RecurringExpenseResponse(Long id, String name, BigDecimal amount, String categoryName,
			Frequency frequency, Integer dayOfMonth, Integer dayOfWeek, Integer monthOfYear,
			boolean active, String currency, BigDecimal exchangeRate) {
		this(id, name, amount, categoryName, frequency, dayOfMonth, dayOfWeek, monthOfYear, active,
				currency, exchangeRate, false, null);
	}
}
