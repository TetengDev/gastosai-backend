package com.teng.app.gastosai.bootstrap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ExpenseSampleData {

	private ExpenseSampleData() {
	}

	public record SampleExpense(BigDecimal amount, String categoryName, LocalDate date, String note) {
	}

	public static List<SampleExpense> samples() {
		return List.of(
				new SampleExpense(new BigDecimal("89.50"),   "Meal Plan",         LocalDate.parse("2026-03-28"), "Groceries - weekend shop"),
				new SampleExpense(new BigDecimal("245.00"),  "Meal Plan",         LocalDate.parse("2026-03-30"), "Dinner with friends"),
				new SampleExpense(new BigDecimal("120.00"),  "Transporation",     LocalDate.parse("2026-03-31"), "Gas station"),
				new SampleExpense(new BigDecimal("45.00"),   "Transporation",     LocalDate.parse("2026-04-01"), "Metro + bus top-up"),
				new SampleExpense(new BigDecimal("250.50"),  "Meal Plan",         LocalDate.parse("2026-04-01"), "Lunch"),
				new SampleExpense(new BigDecimal("1899.00"), "Monthly Utilities", LocalDate.parse("2026-04-02"), "Rent contribution"),
				new SampleExpense(new BigDecimal("599.00"),  "Monthly Utilities", LocalDate.parse("2026-04-02"), "Electricity"),
				new SampleExpense(new BigDecimal("350.00"),  "Extras",            LocalDate.parse("2026-04-02"), "Clothing"),
				new SampleExpense(new BigDecimal("129.99"),  "Extras",            LocalDate.parse("2026-04-02"), "Streaming subscriptions"),
				new SampleExpense(new BigDecimal("75.00"),   "Hygiene Essentials",LocalDate.parse("2026-04-03"), "Pharmacy"),
				new SampleExpense(new BigDecimal("42.30"),   "Meal Plan",         LocalDate.parse("2026-04-03"), "Coffee & pastries"),
				new SampleExpense(new BigDecimal("2100.00"), "Transporation",     LocalDate.parse("2026-04-03"), "Car service"),
				new SampleExpense(new BigDecimal("15.50"),   "Meal Plan",         LocalDate.parse("2026-04-03"), "Snack"),
				new SampleExpense(new BigDecimal("480.00"),  "Monthly Utilities", LocalDate.parse("2026-04-03"), "Internet"),
				new SampleExpense(new BigDecimal("199.00"),  "Extras",            LocalDate.parse("2026-04-03"), "Electronics accessory")
		);
	}
}
