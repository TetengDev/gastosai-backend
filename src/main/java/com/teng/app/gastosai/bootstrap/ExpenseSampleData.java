package com.teng.app.gastosai.bootstrap;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class ExpenseSampleData {

	private ExpenseSampleData() {
	}

	public record SampleExpense(BigDecimal amount, String categoryName, LocalDateTime date, String description) {
	}

	public static List<SampleExpense> samples() {
		return List.of(
				new SampleExpense(new BigDecimal("89.50"),   "Meal Plan",          LocalDateTime.of(2026, 3, 28,  8, 30), "Groceries - weekend shop"),
				new SampleExpense(new BigDecimal("245.00"),  "Meal Plan",          LocalDateTime.of(2026, 3, 30, 19, 15), "Dinner with friends"),
				new SampleExpense(new BigDecimal("120.00"),  "Transporation",      LocalDateTime.of(2026, 3, 31,  7, 45), "Gas station"),
				new SampleExpense(new BigDecimal("45.00"),   "Transporation",      LocalDateTime.of(2026, 4,  1,  8,  0), "Metro + bus top-up"),
				new SampleExpense(new BigDecimal("250.50"),  "Meal Plan",          LocalDateTime.of(2026, 4,  1, 12, 30), "Lunch"),
				new SampleExpense(new BigDecimal("1899.00"), "Monthly Utilities",  LocalDateTime.of(2026, 4,  2,  9,  0), "Rent contribution"),
				new SampleExpense(new BigDecimal("599.00"),  "Monthly Utilities",  LocalDateTime.of(2026, 4,  2, 10, 30), "Electricity"),
				new SampleExpense(new BigDecimal("350.00"),  "Extras",             LocalDateTime.of(2026, 4,  2, 14,  0), "Clothing"),
				new SampleExpense(new BigDecimal("129.99"),  "Extras",             LocalDateTime.of(2026, 4,  2, 20,  0), "Streaming subscriptions"),
				new SampleExpense(new BigDecimal("75.00"),   "Hygiene Essentials", LocalDateTime.of(2026, 4,  3, 10,  0), "Pharmacy"),
				new SampleExpense(new BigDecimal("42.30"),   "Meal Plan",          LocalDateTime.of(2026, 4,  3, 15,  0), "Coffee & pastries"),
				new SampleExpense(new BigDecimal("2100.00"), "Transporation",      LocalDateTime.of(2026, 4,  3,  9, 30), "Car service"),
				new SampleExpense(new BigDecimal("15.50"),   "Meal Plan",          LocalDateTime.of(2026, 4,  3, 16, 45), "Snack"),
				new SampleExpense(new BigDecimal("480.00"),  "Monthly Utilities",  LocalDateTime.of(2026, 4,  3, 11,  0), "Internet"),
				new SampleExpense(new BigDecimal("199.00"),  "Extras",             LocalDateTime.of(2026, 4,  3, 17, 30), "Electronics accessory")
		);
	}
}
