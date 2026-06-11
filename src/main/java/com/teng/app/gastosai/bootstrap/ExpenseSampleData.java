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
				// January 2026
				new SampleExpense(new BigDecimal("1900.00"), "Monthly Utilities",    LocalDateTime.of(2026, 1,  1,  9,  0), "Rent"),
				new SampleExpense(new BigDecimal("548.00"),  "Monthly Utilities",    LocalDateTime.of(2026, 1,  5, 10,  0), "Electricity bill"),
				new SampleExpense(new BigDecimal("480.00"),  "Monthly Utilities",    LocalDateTime.of(2026, 1,  5, 10, 30), "Internet subscription"),
				new SampleExpense(new BigDecimal("100.00"),  "Transportation",       LocalDateTime.of(2026, 1,  6,  7, 30), "Commute top-up"),
				new SampleExpense(new BigDecimal("850.00"),  "Meal Plan",            LocalDateTime.of(2026, 1, 10, 11,  0), "Weekly groceries"),
				new SampleExpense(new BigDecimal("145.00"),  "Meal Plan",            LocalDateTime.of(2026, 1, 15, 12, 30), "Lunch out"),
				new SampleExpense(new BigDecimal("165.00"),  "Hygiene Essentials",   LocalDateTime.of(2026, 1, 17, 15,  0), "Shampoo and soap"),
				new SampleExpense(new BigDecimal("780.00"),  "Date",                 LocalDateTime.of(2026, 1, 18, 19,  0), "Date night dinner"),
				new SampleExpense(new BigDecimal("45.00"),   "Transportation",       LocalDateTime.of(2026, 1, 24,  8,  0), "Jeepney and bus fare"),

				// February 2026
				new SampleExpense(new BigDecimal("1900.00"), "Monthly Utilities",    LocalDateTime.of(2026, 2,  1,  9,  0), "Rent"),
				new SampleExpense(new BigDecimal("512.00"),  "Monthly Utilities",    LocalDateTime.of(2026, 2,  5, 10,  0), "Electricity bill"),
				new SampleExpense(new BigDecimal("480.00"),  "Monthly Utilities",    LocalDateTime.of(2026, 2,  5, 10, 30), "Internet subscription"),
				new SampleExpense(new BigDecimal("1200.00"), "Transportation",       LocalDateTime.of(2026, 2,  8,  9, 30), "Gas refill"),
				new SampleExpense(new BigDecimal("820.00"),  "Meal Plan",            LocalDateTime.of(2026, 2,  9, 11,  0), "Weekly groceries"),
				new SampleExpense(new BigDecimal("1450.00"), "Date",                 LocalDateTime.of(2026, 2, 14, 19, 30), "Valentine dinner"),
				new SampleExpense(new BigDecimal("220.00"),  "Hygiene Essentials",   LocalDateTime.of(2026, 2, 20, 14,  0), "Toiletries restock"),
				new SampleExpense(new BigDecimal("750.00"),  "Monthly Personal",     LocalDateTime.of(2026, 2, 22,  8,  0), "Gym membership"),
				new SampleExpense(new BigDecimal("299.00"),  "Extras",               LocalDateTime.of(2026, 2, 28, 20,  0), "Streaming subscriptions"),

				// March 2026
				new SampleExpense(new BigDecimal("1900.00"), "Monthly Utilities",    LocalDateTime.of(2026, 3,  1,  9,  0), "Rent"),
				new SampleExpense(new BigDecimal("495.00"),  "Monthly Utilities",    LocalDateTime.of(2026, 3,  5, 10,  0), "Electricity bill"),
				new SampleExpense(new BigDecimal("480.00"),  "Monthly Utilities",    LocalDateTime.of(2026, 3,  5, 10, 30), "Internet subscription"),
				new SampleExpense(new BigDecimal("100.00"),  "Transportation",       LocalDateTime.of(2026, 3,  7,  7, 30), "Commute top-up"),
				new SampleExpense(new BigDecimal("890.00"),  "Meal Plan",            LocalDateTime.of(2026, 3, 10, 11,  0), "Weekly groceries"),
				new SampleExpense(new BigDecimal("135.00"),  "Meal Plan",            LocalDateTime.of(2026, 3, 15, 12,  0), "Lunch out"),
				new SampleExpense(new BigDecimal("310.00"),  "Cleaning Essentials",  LocalDateTime.of(2026, 3, 17, 15,  0), "Cleaning supplies"),
				new SampleExpense(new BigDecimal("1500.00"), "Family Contributions", LocalDateTime.of(2026, 3, 20, 16,  0), "Monthly family allowance"),
				new SampleExpense(new BigDecimal("2100.00"), "Transportation",       LocalDateTime.of(2026, 3, 28,  9, 30), "Car service and oil change"),

				// April 2026
				new SampleExpense(new BigDecimal("1900.00"), "Monthly Utilities",    LocalDateTime.of(2026, 4,  1,  9,  0), "Rent"),
				new SampleExpense(new BigDecimal("530.00"),  "Monthly Utilities",    LocalDateTime.of(2026, 4,  5, 10,  0), "Electricity bill"),
				new SampleExpense(new BigDecimal("480.00"),  "Monthly Utilities",    LocalDateTime.of(2026, 4,  5, 10, 30), "Internet subscription"),
				new SampleExpense(new BigDecimal("80.00"),   "Transportation",       LocalDateTime.of(2026, 4,  7,  7, 45), "Commute fare"),
				new SampleExpense(new BigDecimal("760.00"),  "Meal Plan",            LocalDateTime.of(2026, 4,  9, 11,  0), "Weekly groceries"),
				new SampleExpense(new BigDecimal("135.00"),  "Meal Plan",            LocalDateTime.of(2026, 4, 12,  9,  0), "Coffee and pastries"),
				new SampleExpense(new BigDecimal("599.00"),  "Training/Upskilling",  LocalDateTime.of(2026, 4, 15, 14,  0), "Online course"),
				new SampleExpense(new BigDecimal("950.00"),  "Extras",               LocalDateTime.of(2026, 4, 20, 16,  0), "Clothing"),
				new SampleExpense(new BigDecimal("25.00"),   "Transaction Fees",     LocalDateTime.of(2026, 4, 25, 11,  0), "Bank transfer fee"),

				// May 2026
				new SampleExpense(new BigDecimal("1900.00"), "Monthly Utilities",    LocalDateTime.of(2026, 5,  1,  9,  0), "Rent"),
				new SampleExpense(new BigDecimal("560.00"),  "Monthly Utilities",    LocalDateTime.of(2026, 5,  5, 10,  0), "Electricity bill"),
				new SampleExpense(new BigDecimal("480.00"),  "Monthly Utilities",    LocalDateTime.of(2026, 5,  5, 10, 30), "Internet subscription"),
				new SampleExpense(new BigDecimal("1150.00"), "Transportation",       LocalDateTime.of(2026, 5,  8,  9, 30), "Gas refill"),
				new SampleExpense(new BigDecimal("870.00"),  "Meal Plan",            LocalDateTime.of(2026, 5, 11, 11,  0), "Weekly groceries"),
				new SampleExpense(new BigDecimal("175.00"),  "Meal Plan",            LocalDateTime.of(2026, 5, 16, 12, 30), "Lunch out"),
				new SampleExpense(new BigDecimal("245.00"),  "Hygiene Essentials",   LocalDateTime.of(2026, 5, 22, 14,  0), "Sunscreen and lotion"),
				new SampleExpense(new BigDecimal("3200.00"), "Vacation",             LocalDateTime.of(2026, 5, 22, 16,  0), "Hotel for long weekend"),
				new SampleExpense(new BigDecimal("320.00"),  "Meal Plan",            LocalDateTime.of(2026, 5, 23, 13,  0), "Meals during trip"),

				// June 2026
				new SampleExpense(new BigDecimal("1900.00"), "Monthly Utilities",    LocalDateTime.of(2026, 6,  1,  9,  0), "Rent"),
				new SampleExpense(new BigDecimal("543.00"),  "Monthly Utilities",    LocalDateTime.of(2026, 6,  5, 10,  0), "Electricity bill"),
				new SampleExpense(new BigDecimal("480.00"),  "Monthly Utilities",    LocalDateTime.of(2026, 6,  5, 10, 30), "Internet subscription"),
				new SampleExpense(new BigDecimal("90.00"),   "Transportation",       LocalDateTime.of(2026, 6,  7,  7, 45), "Commute fare"),
				new SampleExpense(new BigDecimal("810.00"),  "Meal Plan",            LocalDateTime.of(2026, 6,  9, 11,  0), "Weekly groceries"),
				new SampleExpense(new BigDecimal("155.00"),  "Meal Plan",            LocalDateTime.of(2026, 6, 12, 12, 30), "Lunch out"),
				new SampleExpense(new BigDecimal("750.00"),  "Monthly Personal",     LocalDateTime.of(2026, 6, 15,  8,  0), "Gym membership renewal"),
				new SampleExpense(new BigDecimal("420.00"),  "Training/Upskilling",  LocalDateTime.of(2026, 6, 18, 14,  0), "Books and study materials"),
				new SampleExpense(new BigDecimal("180.00"),  "Uncategorized",        LocalDateTime.of(2026, 6, 22, 15, 30), "Miscellaneous items")
		);
	}
}
