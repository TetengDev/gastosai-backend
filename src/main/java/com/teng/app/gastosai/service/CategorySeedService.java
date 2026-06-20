package com.teng.app.gastosai.service;

import com.teng.app.gastosai.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategorySeedService {

	private static final List<String> PREDEFINED_CATEGORIES = List.of(
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

	private final CategoryService categoryService;

	@Transactional
	public void seedPredefinedForUser(User user) {
		for (String name : PREDEFINED_CATEGORIES) {
			categoryService.getOrCreateByName(name, user);
		}
	}
}
