package com.teng.app.gastosai.bootstrap;

import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(-1)
@RequiredArgsConstructor
public class CategoryDataLoader implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(CategoryDataLoader.class);

	public static final List<String> PREDEFINED_CATEGORIES = List.of(
			"Cleaning Essentials",
			"Date",
			"Extras",
			"Family Contributions",
			"Hygiene Essentials",
			"Meal Plan",
			"Monthly Personal",
			"Monthly Utilities",
			"Training/Upskilling",
			"Transaction Fees",
			"Transportation",
			"Uncategorized",
			"Vacation"
	);

	private final CategoryRepository categoryRepository;

	@Override
	public void run(ApplicationArguments args) {
		long created = PREDEFINED_CATEGORIES.stream()
				.filter(name -> categoryRepository.findByNameIgnoreCase(name).isEmpty())
				.map(name -> categoryRepository.save(Category.builder().name(name).build()))
				.count();
		log.info("Predefined categories: {} created, {} already present",
				created, PREDEFINED_CATEGORIES.size() - created);
	}
}