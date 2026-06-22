package com.teng.app.gastosai.bootstrap;

import com.teng.app.gastosai.entity.Budget;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.Frequency;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.RecurringExpense;
import com.teng.app.gastosai.entity.SavingsGoal;
import com.teng.app.gastosai.entity.SubscriptionPlan;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.entity.UserSubscription;
import com.teng.app.gastosai.repository.BudgetRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
import com.teng.app.gastosai.repository.SavingsGoalRepository;
import com.teng.app.gastosai.repository.SubscriptionPlanRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.repository.UserSubscriptionRepository;
import com.teng.app.gastosai.service.CategorySeedService;
import com.teng.app.gastosai.service.CategoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(1)
public class AppDataLoader implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AppDataLoader.class);

	private final ExpenseRepository expenseRepository;
	private final CategoryService categoryService;
	private final CategorySeedService categorySeedService;
	private final UserRepository userRepository;
	private final BudgetRepository budgetRepository;
	private final RecurringExpenseRepository recurringExpenseRepository;
	private final SavingsGoalRepository savingsGoalRepository;
	private final SubscriptionPlanRepository subscriptionPlanRepository;
	private final UserSubscriptionRepository userSubscriptionRepository;
	private final PasswordEncoder passwordEncoder;
	private final String demoName;
	private final String demoEmail;
	private final String demoPassword;
	private final boolean seedSampleData;
	private final String adminEmail;

	public AppDataLoader(
			ExpenseRepository expenseRepository,
			CategoryService categoryService,
			CategorySeedService categorySeedService,
			UserRepository userRepository,
			BudgetRepository budgetRepository,
			RecurringExpenseRepository recurringExpenseRepository,
			SavingsGoalRepository savingsGoalRepository,
			SubscriptionPlanRepository subscriptionPlanRepository,
			UserSubscriptionRepository userSubscriptionRepository,
			PasswordEncoder passwordEncoder,
			@Value("${gastos.demo.name:Demo User}") String demoName,
			@Value("${gastos.demo.email:demo@gastosai.dev}") String demoEmail,
			@Value("${gastos.demo.password:demo123}") String demoPassword,
			@Value("${gastos.seed-sample-data:false}") boolean seedSampleData,
			@Value("${gastos.admin.email:}") String adminEmail) {
		this.expenseRepository = expenseRepository;
		this.categoryService = categoryService;
		this.categorySeedService = categorySeedService;
		this.userRepository = userRepository;
		this.budgetRepository = budgetRepository;
		this.recurringExpenseRepository = recurringExpenseRepository;
		this.savingsGoalRepository = savingsGoalRepository;
		this.subscriptionPlanRepository = subscriptionPlanRepository;
		this.userSubscriptionRepository = userSubscriptionRepository;
		this.passwordEncoder = passwordEncoder;
		this.demoName = demoName;
		this.demoEmail = demoEmail;
		this.demoPassword = demoPassword;
		this.seedSampleData = seedSampleData;
		this.adminEmail = adminEmail;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (seedSampleData) {
			seedAll(getOrCreateDemoUser(), PlanKey.PREMIUM);
			// Per-tier test accounts so each subscription level is testable by logging in
			// (in addition to the admin "view as" toggle).
			seedAll(getOrCreateUser("free@gastosai.dev", "free123", "Free Tester"), PlanKey.FREE);
			seedAll(getOrCreateUser("premium@gastosai.dev", "premium123", "Premium Tester"), PlanKey.PREMIUM);
			seedAll(getOrCreateUser("trial@gastosai.dev", "trial123", "Trial Tester"), PlanKey.TRIAL);
		}
		if (adminEmail != null && !adminEmail.isBlank()) {
			userRepository.findByEmail(adminEmail).ifPresent(u -> seedAll(u, PlanKey.PREMIUM));
		}
	}

	private void seedAll(User user, PlanKey planKey) {
		categorySeedService.seedPredefinedForUser(user);
		seedExpensesIfEmpty(user);
		seedBudgetsIfEmpty(user);
		seedRecurringIfEmpty(user);
		seedGoalsIfEmpty(user);
		seedSubscription(user, planKey);
	}

	private void seedSubscription(User user, PlanKey planKey) {
		if (userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user).isPresent()) {
			return;
		}
		SubscriptionPlan plan = subscriptionPlanRepository.findByPlanKey(planKey).orElse(null);
		if (plan == null) {
			log.warn("Skipping subscription seed: {} plan not yet seeded", planKey);
			return;
		}
		boolean trial = planKey == PlanKey.TRIAL;
		userSubscriptionRepository.save(UserSubscription.builder()
				.user(user)
				.plan(plan)
				.status(trial ? SubscriptionStatus.TRIAL : SubscriptionStatus.ACTIVE)
				.startedAt(LocalDateTime.now())
				.currentPeriodEnd(trial ? LocalDateTime.now().plusDays(14) : null)
				.provider("seed")
				.build());
		log.info("Seeded {} subscription for {}", planKey, user.getEmail());
	}

	private User getOrCreateDemoUser() {
		return getOrCreateUser(demoEmail, demoPassword, demoName);
	}

	private User getOrCreateUser(String email, String password, String name) {
		return userRepository.findByEmail(email).orElseGet(() -> {
			User saved = userRepository.save(User.builder()
					.name(name)
					.email(email)
					.password(passwordEncoder.encode(password))
					.build());
			log.info("Account created — email: {} / password: {}", email, password);
			return saved;
		});
	}

	private void seedExpensesIfEmpty(User demoUser) {
		Map<String, Category> byName = new HashMap<>();
		List<ExpenseSampleData.SampleExpense> samples = ExpenseSampleData.samples();

		samples.forEach(sample -> {
			String trimmed = sample.categoryName().trim();
			byName.computeIfAbsent(trimmed, k ->
					categoryService.getOrCreateByName(trimmed, demoUser));
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
		String currentMonth = YearMonth.now().toString();
		if (!budgetRepository.findAllByUserAndMonth(user, currentMonth).isEmpty()) {
			log.info("Skipping budget seed: budgets already exist for demo user ({})", currentMonth);
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
			Category cat = categoryService.getOrCreateByName(seed.categoryName(), user);
			budgetRepository.save(Budget.builder()
					.user(user)
					.category(cat)
					.month(currentMonth)
					.amountLimit(new BigDecimal(seed.amount()))
					.amountLimitInBaseCurrency(new BigDecimal(seed.amount()))
					.build());
			count++;
		}
		log.info("Loaded {} sample budgets for demo user ({})", count, currentMonth);
	}

	private void seedGoalsIfEmpty(User user) {
		if (!savingsGoalRepository.findAllByUserOrderByCreatedAtDesc(user).isEmpty()) {
			log.info("Skipping goal seed: goals already exist for demo user");
			return;
		}

		record GoalSeed(String name, String target, String saved, LocalDate targetDate) {}
		List<GoalSeed> seeds = List.of(
				new GoalSeed("Emergency Fund",   "50000.00", "12500.00", LocalDate.of(2026, 12, 31)),
				new GoalSeed("New Laptop",       "45000.00",  "9000.00", LocalDate.of(2026, 9,  30)),
				new GoalSeed("Vacation — Cebu",  "20000.00",  "5000.00", LocalDate.of(2026, 8,  15))
		);

		for (GoalSeed seed : seeds) {
			savingsGoalRepository.save(SavingsGoal.builder()
					.user(user)
					.name(seed.name())
					.targetAmount(new BigDecimal(seed.target()))
					.savedAmount(new BigDecimal(seed.saved()))
					.targetDate(seed.targetDate())
					.paused(false)
					.build());
		}
		log.info("Loaded {} sample goals for demo user", seeds.size());
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
			Category cat = categoryService.getOrCreateByName(seed.categoryName(), user);
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
