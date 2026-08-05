package com.teng.app.gastosai.bootstrap;

import com.teng.app.gastosai.config.JacksonTimeConfig;
import com.teng.app.gastosai.entity.ExpenseType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * The demo expense set, generated <em>relative to today</em> rather than pinned to fixed
 * calendar dates.
 *
 * <p>The reason is that every client's dashboard asks for the <em>current</em> month —
 * {@code /expenses/report/daily}, {@code /expenses/report/top} and
 * {@code /expenses/report/monthly-comparison} are all called with {@code YearMonth.now()}, and the
 * budgets seeded by {@link AppDataLoader} are for {@code YearMonth.now()} too. A fixed set goes
 * stale the moment the calendar moves past it and every one of those cards renders empty, which is
 * exactly what the seed exists to prevent.
 *
 * <p>So the set is built from a monthly backbone that repeats across {@link #MONTHS_OF_HISTORY}
 * months, plus per-month highlights that keep the months distinguishable (month-over-month
 * comparison needs them to differ) and cover the shapes the clients must render: business and
 * reimbursable expenses, and foreign-currency rows with an exchange rate.
 *
 * <p>Nothing is dated in the future. The current month is truncated at today, so a database seeded
 * on the 1st gets the day-1 cluster and a database seeded on the 25th gets most of the month.
 */
public final class ExpenseSampleData {

	/** Current month plus five before it — enough for a month-over-month trend line. */
	private static final int MONTHS_OF_HISTORY = 6;

	private ExpenseSampleData() {
	}

	public record SampleExpense(BigDecimal amount, String categoryName, LocalDateTime date, String description,
			ExpenseType expenseType, boolean reimbursable, String currency, BigDecimal exchangeRate) {

		public SampleExpense(BigDecimal amount, String categoryName, LocalDateTime date, String description) {
			this(amount, categoryName, date, description, ExpenseType.PERSONAL, false, "PHP", BigDecimal.ONE);
		}

		public SampleExpense(BigDecimal amount, String categoryName, LocalDateTime date, String description,
				ExpenseType expenseType, boolean reimbursable) {
			this(amount, categoryName, date, description, expenseType, reimbursable, "PHP", BigDecimal.ONE);
		}
	}

	/**
	 * One entry in a month template. {@code day} is the day of month; {@code hour}/{@code minute}
	 * only exist so the rows sort sensibly within a day.
	 */
	private record Entry(int day, int hour, int minute, String amount, String categoryName, String description,
			ExpenseType expenseType, boolean reimbursable, String currency, String exchangeRate) {

		Entry(int day, int hour, int minute, String amount, String categoryName, String description) {
			this(day, hour, minute, amount, categoryName, description, ExpenseType.PERSONAL, false, "PHP", "1");
		}

		Entry(int day, int hour, int minute, String amount, String categoryName, String description,
				ExpenseType expenseType, boolean reimbursable) {
			this(day, hour, minute, amount, categoryName, description, expenseType, reimbursable, "PHP", "1");
		}

		SampleExpense at(YearMonth month) {
			return new SampleExpense(new BigDecimal(amount), categoryName,
					month.atDay(day).atTime(hour, minute), description,
					expenseType, reimbursable, currency, new BigDecimal(exchangeRate));
		}
	}

	/**
	 * Repeats every month. Days 1–2 are deliberately dense so that a database seeded on the very
	 * first of the month still has something on the dashboard, the daily trend and the
	 * top-expenses card. Nothing goes past day 28, so February needs no special handling.
	 */
	private static final List<Entry> MONTHLY_BACKBONE = List.of(
			new Entry( 1,  9,  0, "1900.00", "Monthly Utilities",   "Rent"),
			new Entry( 1,  8, 30,   "85.00", "Meal Plan",           "Coffee and pastries"),
			new Entry( 1,  7, 45,   "90.00", "Transportation",      "Commute fare"),
			new Entry( 2, 11,  0,  "810.00", "Meal Plan",           "Weekly groceries"),
			new Entry( 3, 12, 30,  "160.00", "Meal Plan",           "Lunch out"),
			new Entry( 4,  7, 45,   "90.00", "Transportation",      "Commute fare"),
			new Entry( 5, 10, 30,  "480.00", "Monthly Utilities",   "Internet subscription"),
			new Entry( 6, 19,  0,  "220.00", "Meal Plan",           "Dinner"),
			new Entry( 7,  7, 45,   "90.00", "Transportation",      "Commute fare"),
			new Entry( 8,  9,  0,   "70.00", "Meal Plan",           "Coffee"),
			new Entry( 9, 11,  0,  "820.00", "Meal Plan",           "Weekly groceries"),
			new Entry(10, 12, 30,  "145.00", "Meal Plan",           "Lunch"),
			new Entry(11,  7, 45,   "85.00", "Transportation",      "Commute fare"),
			new Entry(12, 12, 30,  "155.00", "Meal Plan",           "Lunch out"),
			new Entry(13, 19,  0,  "650.00", "Meal Plan",           "Client dinner", ExpenseType.BUSINESS, true),
			new Entry(14,  8, 30,   "75.00", "Meal Plan",           "Coffee"),
			new Entry(15,  8,  0,  "750.00", "Monthly Personal",    "Gym membership"),
			new Entry(16,  9,  0,   "55.00", "Meal Plan",           "Snacks"),
			new Entry(17, 11,  0,  "790.00", "Meal Plan",           "Weekly groceries"),
			new Entry(18, 14,  0,  "420.00", "Training/Upskilling", "Books and study materials"),
			new Entry(19, 12, 30,  "130.00", "Meal Plan",           "Lunch"),
			new Entry(20, 20,  0,  "299.00", "Extras",              "Streaming subscriptions"),
			new Entry(21,  7, 45,   "85.00", "Transportation",      "Commute fare"),
			new Entry(22, 15, 30,  "180.00", "Uncategorized",       "Miscellaneous items"),
			new Entry(23, 11,  0,  "780.00", "Meal Plan",           "Weekly groceries"),
			new Entry(24,  8, 30,   "75.00", "Meal Plan",           "Coffee"),
			new Entry(25, 11,  0,   "25.00", "Transaction Fees",    "Bank transfer fee"),
			new Entry(26, 14,  0,  "220.00", "Hygiene Essentials",  "Toiletries restock"),
			new Entry(27, 15,  0,  "310.00", "Cleaning Essentials", "Cleaning supplies"),
			new Entry(28, 16,  0, "1500.00", "Family Contributions", "Monthly family allowance"));

	/** The electricity bill, varied per month so the utilities line is not perfectly flat. */
	private static final String[] ELECTRICITY_BY_MONTHS_AGO = {
			"543.00", "560.00", "530.00", "495.00", "512.00", "548.00" };

	/**
	 * Month-specific rows, indexed by how many months ago the month is (0 = the current month).
	 * The current month's highlights sit on days 2 and 4 so they survive the truncation at today
	 * for most of the month.
	 */
	private static List<Entry> highlightsFor(int monthsAgo) {
		return switch (monthsAgo) {
			case 0 -> List.of(
					new Entry( 2, 19,  0,  "780.00", "Date",                "Date night dinner"),
					new Entry( 4,  9, 30, "1200.00", "Transportation",      "Gas refill"));
			case 1 -> List.of(
					new Entry(14, 19, 30,  "780.00", "Date",                "Date night dinner"),
					new Entry( 8,  9, 30, "1150.00", "Transportation",      "Gas refill"),
					new Entry(22, 14,  0,   "22.00", "Extras",              "EUR subscription",
							ExpenseType.PERSONAL, false, "EUR", "62.30"));
			case 2 -> List.of(
					new Entry(15, 14,  0,  "599.00", "Training/Upskilling", "Online course"),
					new Entry(18, 10,  0, "1200.00", "Training/Upskilling", "Tech conference registration",
							ExpenseType.BUSINESS, false),
					new Entry(20, 16,  0,  "950.00", "Extras",              "Clothing"));
			case 3 -> List.of(
					new Entry(22, 16,  0, "3200.00", "Vacation",            "Hotel for long weekend"),
					new Entry(24, 14,  0,   "60.00", "Vacation",            "USD hotel deposit",
							ExpenseType.PERSONAL, false, "USD", "57.75"),
					new Entry(23, 13,  0,  "320.00", "Meal Plan",           "Meals during trip"));
			case 4 -> List.of(
					new Entry(19,  9,  0, "2400.00", "Training/Upskilling",
							"Work laptop stand and peripherals", ExpenseType.BUSINESS, true),
					new Entry(28, 10,  0,   "35.00", "Training/Upskilling", "SGD course material",
							ExpenseType.PERSONAL, false, "SGD", "42.10"));
			default -> List.of(
					new Entry(28,  9, 30, "2100.00", "Transportation",      "Car service and oil change"),
					new Entry(14, 19, 30, "1450.00", "Date",                "Anniversary dinner"),
					new Entry(26, 12,  0, "1500.00", "Meal Plan",           "JPY meal plan import",
							ExpenseType.PERSONAL, false, "JPY", "0.385"));
		};
	}

	/** The demo set as of today in the application's zone. */
	public static List<SampleExpense> samples() {
		return samples(LocalDate.now(JacksonTimeConfig.APP_ZONE));
	}

	/**
	 * The demo set as of {@code today}: {@link #MONTHS_OF_HISTORY} months ending with the month
	 * containing {@code today}, with that last month truncated so no expense is dated in the future.
	 */
	public static List<SampleExpense> samples(LocalDate today) {
		YearMonth currentMonth = YearMonth.from(today);
		List<SampleExpense> samples = new ArrayList<>();

		for (int monthsAgo = MONTHS_OF_HISTORY - 1; monthsAgo >= 0; monthsAgo--) {
			YearMonth month = currentMonth.minusMonths(monthsAgo);
			int lastDay = (monthsAgo == 0) ? today.getDayOfMonth() : month.lengthOfMonth();

			List<Entry> entries = new ArrayList<>(MONTHLY_BACKBONE);
			entries.add(new Entry(5, 10, 0, electricityFor(monthsAgo), "Monthly Utilities", "Electricity bill"));
			entries.addAll(highlightsFor(monthsAgo));

			entries.stream()
					.filter(entry -> entry.day() <= lastDay)
					.sorted(java.util.Comparator.comparingInt(Entry::day).thenComparingInt(Entry::hour)
							.thenComparingInt(Entry::minute))
					.map(entry -> entry.at(month))
					.forEach(samples::add);
		}
		return List.copyOf(samples);
	}

	private static String electricityFor(int monthsAgo) {
		return ELECTRICITY_BY_MONTHS_AGO[monthsAgo % ELECTRICITY_BY_MONTHS_AGO.length];
	}
}
