package com.teng.app.gastosai.bootstrap;

import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(0)
@ConditionalOnProperty(name = "gastos.seed-sample-data", havingValue = "true")
@RequiredArgsConstructor
public class AppDataLoader implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AppDataLoader.class);

	private static final String DEMO_EMAIL = "demo@gastosai.dev";
	private static final String DEMO_PASSWORD = "demo123";

	private final ExpenseRepository expenseRepository;
	private final CategoryRepository categoryRepository;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;

	@Override
	public void run(ApplicationArguments args) {
		User demoUser = getOrCreateDemoUser();
		seedExpensesIfEmpty(demoUser);
	}

	private User getOrCreateDemoUser() {
		return userRepository.findByEmail(DEMO_EMAIL).orElseGet(() -> {
			User user = User.builder()
					.name("Demo User")
					.email(DEMO_EMAIL)
					.password(passwordEncoder.encode(DEMO_PASSWORD))
					.build();
			User saved = userRepository.save(user);
			log.info("Demo account created — email: {} / password: {}", DEMO_EMAIL, DEMO_PASSWORD);
			return saved;
		});
	}

	private void seedExpensesIfEmpty(User demoUser) {
		Map<String, Category> byName = new HashMap<>();
		List<ExpenseSampleData.SampleExpense> samples = ExpenseSampleData.samples();

		samples.forEach(sample -> {
			String trimmed = sample.categoryName().trim();
			byName.computeIfAbsent(trimmed, k ->
					categoryRepository.findByName(k)
							.orElseGet(() -> categoryRepository.save(
									Category.builder()
											.name(trimmed)
											.build())));
		});

		long expenseCount = expenseRepository.findAllByUser(demoUser).size();
		if (expenseCount > 0) {
			log.info("Skipping sample expense seed: {} row(s) already exist for demo user", expenseCount);
			return;
		}

		var expensesToSave = samples.stream()
				.map(sample -> {
					String trimmed = sample.categoryName().trim();
					Category category = byName.get(trimmed);
					return Expense.builder()
							.amount(sample.amount())
							.user(demoUser)
							.category(category)
							.date(sample.date())
							.description(sample.description())
							.build();
				})
				.toList();

		expenseRepository.saveAll(expensesToSave);
		log.info("Loaded {} sample expenses for demo user", expensesToSave.size());
	}
}
