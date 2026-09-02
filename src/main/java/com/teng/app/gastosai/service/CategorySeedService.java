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

	/**
	 * Write the starter set for a newly registered account. Called by every registration path —
	 * password, magic link and Google — and by the local {@code AppDataLoader}.
	 *
	 * <p>These rows go in as <em>system-provided</em> (TEN-327), so they neither consume the plan's
	 * category cap nor are refused by it. The account is still FREE when this runs — seeding
	 * happens before {@code SubscriptionService.startTrial} — and the FREE cap is 5 against 13
	 * starters, so routing this through the user-facing {@code getOrCreateByName} made registration
	 * fail at the sixth the moment {@code gastos.monetization.enforce=true} was set. A FREE account
	 * now keeps all 13 and may still create 5 of its own.
	 *
	 * <p>Idempotent: a name the user already has resolves to that row and is left exactly as it is,
	 * flag included.
	 */
	@Transactional
	public void seedPredefinedForUser(User user) {
		for (String name : PREDEFINED_CATEGORIES) {
			categoryService.getOrCreateSystemProvided(name, user);
		}
	}
}
