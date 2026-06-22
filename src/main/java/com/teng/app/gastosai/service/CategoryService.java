package com.teng.app.gastosai.service;

import com.teng.app.gastosai.config.CategoryLimitProperties;
import com.teng.app.gastosai.config.MonetizationProperties;
import com.teng.app.gastosai.dto.CategoryRequest;
import com.teng.app.gastosai.dto.CategoryResponse;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.FeatureLockedException;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService {

	private static final String DEFAULT_CATEGORY = "Uncategorized";

	private final CategoryRepository categoryRepository;
	private final ExpenseRepository expenseRepository;
	private final MonetizationProperties monetizationProperties;
	private final CategoryLimitProperties categoryLimits;
	private final EntitlementService entitlementService;

	@Transactional
	public CategoryResponse create(CategoryRequest request, User user) {
		String trimmed = request.name().trim();
		if (categoryRepository.existsByUserAndNameIgnoreCase(user, trimmed)) {
			throw new IllegalArgumentException("Category already exists: " + request.name());
		}
		enforceCategoryLimit(user);
		Category saved = categoryRepository.save(Category.builder()
				.name(trimmed)
				.icon(request.icon() != null ? request.icon().trim() : null)
				.user(user)
				.build());
		return toResponse(saved);
	}

	@Transactional(readOnly = true)
	public List<CategoryResponse> findAll(User user) {
		return categoryRepository.findAllByUser(user).stream()
				.sorted((a, b) -> {
					boolean aDef = isDefault(a.getName());
					boolean bDef = isDefault(b.getName());
					if (aDef != bDef) return aDef ? -1 : 1;
					return a.getName().compareToIgnoreCase(b.getName());
				})
				.map(this::toResponse)
				.toList();
	}

	@Transactional(readOnly = true)
	public CategoryResponse findById(Long id, User user) {
		return categoryRepository.findByIdAndUser(id, user)
				.map(this::toResponse)
				.orElseThrow(() -> new ResourceNotFoundException("Category not found: " + id));
	}

	@Transactional
	public CategoryResponse update(Long id, CategoryRequest request, User user) {
		Category existing = categoryRepository.findByIdAndUser(id, user)
				.orElseThrow(() -> new ResourceNotFoundException("Category not found: " + id));

		String trimmed = request.name().trim();
		Category conflicting = categoryRepository.findByUserAndNameIgnoreCase(user, trimmed).orElse(null);
		if (conflicting != null && !conflicting.getId().equals(existing.getId())) {
			throw new IllegalArgumentException("Category already exists: " + request.name());
		}

		existing.setName(trimmed);
		existing.setIcon(request.icon() != null ? request.icon().trim() : null);
		Category saved = categoryRepository.save(existing);
		return toResponse(saved);
	}

	@Transactional
	public void delete(Long id, User user) {
		Category toDelete = categoryRepository.findByIdAndUser(id, user)
				.orElseThrow(() -> new ResourceNotFoundException("Category not found: " + id));
		if (isDefault(toDelete.getName())) {
			throw new IllegalArgumentException("Default categories cannot be deleted");
		}

		List<Expense> affected = expenseRepository.findByCategory_Id(toDelete.getId());
		if (!affected.isEmpty()) {
			Category fallback = getOrCreateByName(DEFAULT_CATEGORY, user);
			affected.forEach(e -> e.setCategory(fallback));
			expenseRepository.saveAll(affected);
		}

		categoryRepository.deleteById(id);
	}

	@Transactional
	public void deleteAllExceptDefault(User user) {
		List<Category> toDelete = categoryRepository.findAllByUser(user).stream()
				.filter(c -> !isDefault(c.getName()))
				.toList();
		if (toDelete.isEmpty()) return;

		Category fallback = getOrCreateByName(DEFAULT_CATEGORY, user);
		for (Category cat : toDelete) {
			List<Expense> affected = expenseRepository.findByCategory_Id(cat.getId());
			if (!affected.isEmpty()) {
				affected.forEach(e -> e.setCategory(fallback));
				expenseRepository.saveAll(affected);
			}
		}
		categoryRepository.deleteAll(toDelete);
	}

	private boolean isDefault(String name) {
		return DEFAULT_CATEGORY.equalsIgnoreCase(name);
	}

	/**
	 * Block creating more categories than the user's plan allows. No-op unless monetization is
	 * enforced. {@link EntitlementService#describe} already folds in the admin/view-as logic, so
	 * admins (and PREMIUM) resolve to an unlimited cap.
	 */
	private void enforceCategoryLimit(User user) {
		if (!monetizationProperties.isEnforce()) {
			return;
		}
		int cap = capFor(entitlementService.describe(user).plan());
		if (cap > 0 && categoryRepository.countByUser(user) >= cap) {
			throw new FeatureLockedException(FeatureKey.CUSTOM_CATEGORIES,
					"Your plan is limited to " + cap + " categories. Upgrade to add more.");
		}
	}

	private int capFor(PlanKey plan) {
		return switch (plan) {
			case FREE -> categoryLimits.getFree();
			case PREMIUM -> categoryLimits.getPremium();
			case TRIAL -> categoryLimits.getTrial();
		};
	}

	@Transactional
	public Category getOrCreateByName(String categoryName, User user) {
		String trimmed = categoryName.trim();
		return categoryRepository.findByUserAndNameIgnoreCase(user, trimmed)
				.orElseGet(() -> categoryRepository.save(Category.builder()
						.name(trimmed)
						.user(user)
						.build()));
	}

	private CategoryResponse toResponse(Category c) {
		return new CategoryResponse(c.getId(), c.getName(), c.getIcon());
	}
}
