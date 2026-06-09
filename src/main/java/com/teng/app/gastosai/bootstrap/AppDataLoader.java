package com.teng.app.gastosai.bootstrap;

import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Startup seeding when {@code gastos.seed-sample-data=true}. Add more seed methods as the app grows.
 */
@Component
@Order(0)
@ConditionalOnProperty(name = "gastos.seed-sample-data", havingValue = "true")
@RequiredArgsConstructor
public class AppDataLoader implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AppDataLoader.class);

	private final ExpenseRepository expenseRepository;
	private final CategoryRepository categoryRepository;

	@Override
	public void run(ApplicationArguments args) {
		seedExpensesIfEmpty();
	}

	private void seedExpensesIfEmpty() {
		// Seed categories and optionally seed expenses.
		long expenseCount = expenseRepository.count();
		Map<String, Category> byName = new HashMap<>();
		List<ExpenseSampleData.SampleExpense> samples = ExpenseSampleData.samples();

		// Ensure categories exist.
		samples.forEach(sample -> {
			String trimmed = sample.categoryName().trim();
			byName.computeIfAbsent(trimmed, k ->
					categoryRepository.findByName(k)
							.orElseGet(() -> categoryRepository.save(
									Category.builder()
											.name(trimmed)
											.build())));
		});

		// Seed expenses only if empty.
		if (expenseCount > 0) {
			log.info("Skipping sample expense seed: {} row(s) already in expenses", expenseCount);
			return;
		}

		var expensesToSave = samples.stream()
				.map(sample -> {
					String trimmed = sample.categoryName().trim();
					Category category = byName.get(trimmed);
					return Expense.builder()
							.amount(sample.amount())
							.category(category)
							.date(sample.date())
							.description(sample.description())
							.build();
				})
				.toList();

		expenseRepository.saveAll(expensesToSave);
		log.info("Loaded {} sample expenses", expenseRepository.count());
	}
}
