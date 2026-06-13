package com.teng.app.gastosai.bootstrap;

import com.teng.app.gastosai.entity.Budget;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.Frequency;
import com.teng.app.gastosai.entity.RecurringExpense;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.BudgetRepository;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
import com.teng.app.gastosai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(1)
@ConditionalOnProperty(name = "gastos.seed-sample-data", havingValue = "true")
public class AppDataLoader implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AppDataLoader.class);

	private final ExpenseRepository expenseRepository;
	private final CategoryRepository categoryRepository;
	private final UserRepository userRepository;
	private final BudgetRepository budgetRepository;
	private final RecurringExpenseRepository recurringExpenseRepository;
	private final PasswordEncoder passwordEncoder;
	private final String demoName;
	private final String demoEmail;
	private final String demoPassword;

	public AppDataLoader(
			ExpenseRepository expenseRepository,
			CategoryRepository categoryRepository,
			UserRepository userRepository,
			BudgetRepository budgetRepository,
			RecurringExpenseRepository recurringExpenseRepository,
			PasswordEncoder passwordEncoder,
			@Value("${gastos.demo.name:Demo User}") String demoName,
			@Value("${gastos.demo.email:demo@gastosai.dev}") String demoEmail,
			@Value("${gastos.demo.password:demo123}") String demoPassword) {
		this.expenseRepository = expenseRepository;
		this.categoryRepository = categoryRepository;
		this.userRepository = userRepository;
		this.budgetRepository = budgetRepository;
		this.recurringExpenseRepository = recurringExpenseRepository;
		this.passwordEncoder = passwordEncoder;
		this.demoName = demoName;
		this.demoEmail = demoEmail;
		this.demoPassword = demoPassword;
	}

	@Override
	public void run(ApplicationArguments args) {
		User demoUser = getOrCreateDemoUser();
		seedExpensesIfEmpty(demoUser);
		seedBudgetsIfEmpty(demoUser);
		seedRecurringIfEmpty(demoUser);
	}

	private User getOrCreateDemoUser() {
		return userRepository.findByEmail(demoEmail).orElseGet(() -> {
			User user = User.builder()
					.name(demoName)
					.email(demoEmail)
					.password(passwordEncoder.encode(demoPassword))
					.build();
			User saved = userRepository.save(user);
			log.info("Demo account created — email: {} / password: {}", demoEmail, demoPassword);
			return saved;
		});
	}

	private void seedExpensesIfEmpty(User demoUser) {
		Map<String, Category> byName = new HashMap<>();
		List<ExpenseSampleData.SampleExpense> samples = ExpenseSampleData.samples();

		samples.forEach(sample -> {
			String trimmed = sample.categoryName().trim();
			byName.computeIfAbsent(trimmed, k ->
					categoryRepository.findByNameIgnoreCase(k)
							.orElseGet(() -> categoryRepository.save(
									Category.builder()
											.name(trimmed)
											.build())));
		});

		long expenseCount = expenseRepository.findAllByUserOrderByDateDesc(demoUser).size();
		if (expenseCount > 0) {
			log.info("Skipping sample expense seed: {} row(s) already exist for demo user", expenseCount);
			return;
		}

		var expensesToSave = samples.stream()
				.map(sample -> {
					String trimmed = sample.categoryName().trim();
					Category category = byName.get(trimmed);
					java.math.BigDecimal rate = sample.exchangeRate();
					java.math.BigDecimal base = sample.amount().multiply(rate)
							.setScale(4, java.math.RoundingMode.HALF_UP);
					return Expense.builder()
							.amount(sample.amount())
							.user(demoUser)
							.category(category)
							.date(sample.date())
							.description(sample.description())
							.expenseType(sample.expenseType())
							.reimbursable(sample.reimbursable())
							.currency(sample.currency())
							.exchangeRate(rate)
							.amountInBaseCurrency(base)
							.build();
				})
				.toList();

		expenseRepository.saveAll(expensesToSave);
		log.info("Loaded {} sample expenses for demo user", expensesToSave.size());
	}

	private void seedBudgetsIfEmpty(User user) {
		if (!budgetRepository.findAllByUserAndMonth(user, "2026-06").isEmpty()) {
			log.info("Skipping budget seed: budgets already exist for demo user (2026-06)");
			return;
		}

		record BudgetSeed(String categoryName, String amount) {}
		List<BudgetSeed> seeds = List.of(
				new BudgetSeed("Meal Plan",          "4000.00"),
				new BudgetSeed("Transportation",     "2500.00"),
				new BudgetSeed("Monthly Utilities",  "4000.00"),
				new BudgetSeed("Extras",             "2000.00"),
				new BudgetSeed("Hygiene Essentials", "1000.00")
		);

		int count = 0;
		for (BudgetSeed seed : seeds) {
			categoryRepository.findByNameIgnoreCase(seed.categoryName()).ifPresent(cat -> {
				budgetRepository.save(Budget.builder()
						.user(user)
						.category(cat)
						.month("2026-06")
						.amountLimit(new BigDecimal(seed.amount()))
						.build());
			});
			count++;
		}
		log.info("Loaded {} sample budgets for demo user (2026-06)", count);
	}

	private void seedRecurringIfEmpty(User user) {
		if (!recurringExpenseRepository.findAllByUser(user).isEmpty()) {
			log.info("Skipping recurring seed: recurring expenses already exist for demo user");
			return;
		}

		record RecurringSeed(String name, String amount, String categoryName,
				Frequency frequency, Integer dayOfMonth, Integer dayOfWeek) {}
		List<RecurringSeed> seeds = List.of(
				new RecurringSeed("Rent",             "1900.00", "Monthly Utilities", Frequency.MONTHLY, 1,    null),
				new RecurringSeed("Electricity",      "550.00",  "Monthly Utilities", Frequency.MONTHLY, 5,    null),
				new RecurringSeed("Internet",         "480.00",  "Monthly Utilities", Frequency.MONTHLY, 5,    null),
				new RecurringSeed("Gym Membership",   "750.00",  "Monthly Personal",  Frequency.MONTHLY, 10,   null),
				new RecurringSeed("Netflix",          "299.00",  "Monthly Personal",  Frequency.MONTHLY, 15,   null),
				new RecurringSeed("Weekly Groceries", "800.00",  "Meal Plan",         Frequency.WEEKLY,  null, 6)
		);

		int count = 0;
		for (RecurringSeed seed : seeds) {
			Category cat = categoryRepository.findByNameIgnoreCase(seed.categoryName()).orElse(null);
			recurringExpenseRepository.save(RecurringExpense.builder()
					.user(user)
					.name(seed.name())
					.amount(new BigDecimal(seed.amount()))
					.category(cat)
					.frequency(seed.frequency())
					.dayOfMonth(seed.dayOfMonth())
					.dayOfWeek(seed.dayOfWeek())
					.build());
			count++;
		}
		log.info("Loaded {} sample recurring expenses for demo user", count);
	}
}
