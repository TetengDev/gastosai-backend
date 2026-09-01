package com.teng.app.gastosai.service;

import com.teng.app.gastosai.config.CategoryLimitProperties;
import com.teng.app.gastosai.config.MonetizationProperties;
import com.teng.app.gastosai.dto.CategoryRequest;
import com.teng.app.gastosai.dto.CategoryResponse;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.CategoryAlias;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.MerchantRule;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.FeatureLockedException;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.CategoryAliasRepository;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.MerchantRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CategoryService {

	private static final String DEFAULT_CATEGORY = "Uncategorized";

	/** Matches the merchant/alias column widths in V26; a longer key is truncated, never rejected. */
	private static final int MAX_MERCHANT_KEY = 100;
	private static final int MAX_ALIAS = 50;

	private final CategoryRepository categoryRepository;
	private final ExpenseRepository expenseRepository;
	private final MerchantRuleRepository merchantRuleRepository;
	private final CategoryAliasRepository categoryAliasRepository;
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

		// Scoped to the category's owner, not to the category alone: the fallback below is created
		// for `user`, so reassigning a row that belongs to somebody else would hand that row a
		// category its owner cannot see. See ExpenseRepository#findByCategory_IdAndUser.
		List<Expense> affected = expenseRepository.findByCategory_IdAndUser(toDelete.getId(), toDelete.getUser());
		if (!affected.isEmpty()) {
			Category fallback = getOrCreateByName(DEFAULT_CATEGORY, user);
			affected.forEach(e -> e.setCategory(fallback));
			expenseRepository.saveAll(affected);
		}

		detachRulesAndAliases(toDelete, user);
		categoryRepository.deleteById(id);
	}

	/**
	 * Clear every category but the default, behind {@code DELETE /categories}.
	 *
	 * <p>One transaction, so an expense of another user still pointing at one of these categories
	 * — the legacy shape V29 repairs — rolls the whole bulk delete back rather than clearing some
	 * categories and not others. Refusing is recoverable; silently reassigning that user's expense
	 * is not.
	 */
	@Transactional
	public void deleteAllExceptDefault(User user) {
		List<Category> toDelete = categoryRepository.findAllByUser(user).stream()
				.filter(c -> !isDefault(c.getName()))
				.toList();
		if (toDelete.isEmpty()) return;

		Category fallback = getOrCreateByName(DEFAULT_CATEGORY, user);
		for (Category cat : toDelete) {
			// Owner-scoped for the same reason as delete(): every category here came from
			// findAllByUser(user), so `user` is the owner.
			List<Expense> affected = expenseRepository.findByCategory_IdAndUser(cat.getId(), user);
			if (!affected.isEmpty()) {
				affected.forEach(e -> e.setCategory(fallback));
				expenseRepository.saveAll(affected);
			}
			detachRulesAndAliases(cat, user);
		}
		categoryRepository.deleteAll(toDelete);
	}

	/**
	 * Drop the rules and aliases that point at a category about to be deleted. The foreign keys
	 * cascade in PostgreSQL anyway, but doing it here keeps the persistence context honest — a
	 * cascade the database performs behind Hibernate's back leaves stale managed entities.
	 */
	private void detachRulesAndAliases(Category category, User user) {
		List<MerchantRule> rules = merchantRuleRepository.findAllByUserAndCategory_Id(user, category.getId());
		if (!rules.isEmpty()) {
			merchantRuleRepository.deleteAll(rules);
		}
		List<CategoryAlias> aliases = categoryAliasRepository.findAllByUserAndCategory_Id(user, category.getId());
		if (!aliases.isEmpty()) {
			categoryAliasRepository.deleteAll(aliases);
		}
	}

	private boolean isDefault(String name) {
		return DEFAULT_CATEGORY.equalsIgnoreCase(name);
	}

	/**
	 * Block creating more categories than the user's plan allows. No-op unless monetization is
	 * enforced. {@link EntitlementService#describe} already folds in the admin/view-as logic, so
	 * admins (and PREMIUM) resolve to an unlimited cap.
	 *
	 * <p>This gates the category management surface — {@code POST /categories} — and nothing else.
	 * It is deliberately <em>not</em> called from {@link #getOrCreateByName}; see that method for
	 * the reasoning and for what would have to change first.
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

	/**
	 * The named category belonging to {@code owner}, created if it does not exist yet.
	 *
	 * <p>{@code owner} is who the category is <em>for</em>, which is not always who asked. An ADMIN
	 * editing another user's expense passes that expense's owner, so the row and its category keep
	 * agreeing on whose they are; a caller that hands in its own principal by reflex would file a
	 * category the owner can never see.
	 *
	 * <p><b>The plan category cap does not apply here, deliberately (TEN-319).</b> This method is
	 * the incidental path: an expense, a chat action, a CSV row or a recurring template names a
	 * category, and the name has to resolve to a row for the write to happen at all. Three reasons
	 * it is exempt rather than capped:
	 *
	 * <ul>
	 *   <li><b>The same method provisions accounts.</b> {@code CategorySeedService} creates the 13
	 *       starter categories through it at registration, before the trial is enrolled, so the
	 *       user is FREE at that moment and the FREE cap is 5. Enforcing here would make the sixth
	 *       seeded category throw and take registration down with it.</li>
	 *   <li><b>The cap's arithmetic is already broken against that seed.</b> A registered FREE user
	 *       holds 13 categories against a cap of 5, so every incidental creation would fail from
	 *       the first one — see {@code EntitlementEnforcementIntegrationTest
	 *       #free_afterRegistrationSeeding_cannotCreateAnyCategory}, which pins that gap.</li>
	 *   <li><b>The failure would land on the wrong request.</b> A cap is a paywall on managing
	 *       categories, not a reason to refuse to record a spend. Failing {@code POST /expenses}
	 *       with 402 because of the category name loses the expense the user was trying to keep.</li>
	 * </ul>
	 *
	 * <p>What the exemption costs: with monetization enforced, a user at their cap can still grow
	 * their category list without limit by naming new ones — on an expense, in chat, in a CSV
	 * import or on a recurring template, since all four reach this method. That is accepted for
	 * now, and {@code TEN-327} tracks re-checking every one of them. Revisiting it
	 * needs the seed-versus-cap gap resolved first (raise the cap above the seeded set, or count
	 * only user-created categories), and the check then belongs at the call sites that carry user
	 * intent — not here, where provisioning also passes through.
	 *
	 * @see #enforceCategoryLimit(User)
	 */
	@Transactional
	public Category getOrCreateByName(String categoryName, User owner) {
		String trimmed = categoryName.trim();
		return resolveByName(trimmed, owner)
				.orElseGet(() -> categoryRepository.save(Category.builder()
						.name(trimmed)
						.user(owner)
						.build()));
	}

	/**
	 * Resolve a name the parser produced to one of the user's categories: the real name first, an
	 * alias second. Name-first is deliberate — if a name is somehow both a category and an alias,
	 * the category the user can see and rename wins over the hidden one.
	 */
	@Transactional(readOnly = true)
	public Optional<Category> resolveByName(String name, User user) {
		if (name == null || name.isBlank()) {
			return Optional.empty();
		}
		String trimmed = name.trim();
		Optional<Category> byName = categoryRepository.findByUserAndNameIgnoreCase(user, trimmed);
		if (byName.isPresent()) {
			return byName;
		}
		return categoryAliasRepository.findByUserAndAlias(user, aliasKey(trimmed))
				.map(CategoryAlias::getCategory);
	}

	// ---------------------------------------------------------------- aliases

	/**
	 * Teach a category to answer to another name while parsing. Rejects an alias that already
	 * belongs to a different category, or that collides with a category's real name — either would
	 * make one spelling resolve two ways.
	 */
	@Transactional
	public void addAlias(Long categoryId, String alias, User user) {
		Category category = categoryRepository.findByIdAndUser(categoryId, user)
				.orElseThrow(() -> new ResourceNotFoundException("Category not found: " + categoryId));
		if (alias == null || alias.isBlank()) {
			throw new IllegalArgumentException("Alias must not be blank");
		}
		String key = aliasKey(alias);
		if (key.equalsIgnoreCase(category.getName())) {
			return; // an alias identical to the category's own name is a no-op, not an error
		}
		categoryRepository.findByUserAndNameIgnoreCase(user, key).ifPresent(clash -> {
			throw new IllegalArgumentException("Alias already names a category: " + clash.getName());
		});
		Optional<CategoryAlias> existing = categoryAliasRepository.findByUserAndAlias(user, key);
		if (existing.isPresent()) {
			if (!existing.get().getCategory().getId().equals(category.getId())) {
				throw new IllegalArgumentException("Alias already belongs to another category: " + alias);
			}
			return; // already this category's alias
		}
		categoryAliasRepository.save(CategoryAlias.builder()
				.user(user)
				.category(category)
				.alias(key)
				.createdAt(LocalDateTime.now())
				.build());
	}

	@Transactional
	public void removeAlias(String alias, User user) {
		if (alias == null || alias.isBlank()) {
			return;
		}
		categoryAliasRepository.deleteByUserAndAlias(user, aliasKey(alias));
	}

	@Transactional(readOnly = true)
	public List<String> aliasesFor(Long categoryId, User user) {
		categoryRepository.findByIdAndUser(categoryId, user)
				.orElseThrow(() -> new ResourceNotFoundException("Category not found: " + categoryId));
		return categoryAliasRepository.findAllByUserAndCategory_Id(user, categoryId).stream()
				.map(CategoryAlias::getAlias)
				.sorted()
				.toList();
	}

	// -------------------------------------------------------- merchant rules

	/**
	 * The category a merchant rule assigns to this description, if any. Tries the exact normalised
	 * key first, then the longest rule that prefixes it on a word boundary — so a rule learned from
	 * "jollibee" still catches "jollibee lunch with mika", while "jollibee" never matches a rule
	 * for "jollibee express".
	 */
	@Transactional(readOnly = true)
	public Optional<Category> resolveByMerchant(String description, User user) {
		String key = merchantKey(description);
		if (key == null) {
			return Optional.empty();
		}
		Optional<MerchantRule> exact = merchantRuleRepository.findByUserAndMerchant(user, key);
		if (exact.isPresent()) {
			return exact.map(MerchantRule::getCategory);
		}
		return merchantRuleRepository.findAllByUser(user).stream()
				.filter(r -> key.startsWith(r.getMerchant() + " "))
				.max(Comparator.comparingInt(r -> r.getMerchant().length()))
				.map(MerchantRule::getCategory);
	}

	/**
	 * Record that this merchant belongs in this category, so the next expense from it categorises
	 * itself. Upserts, so re-categorising a merchant moves its rule rather than duplicating it.
	 * Learning nothing from {@code Uncategorized} is deliberate: "I did not say" is not a rule.
	 */
	@Transactional
	public void learnMerchantRule(String description, Category category, User user) {
		String key = merchantKey(description);
		if (key == null || category == null || isDefault(category.getName())) {
			return;
		}
		LocalDateTime now = LocalDateTime.now();
		MerchantRule rule = merchantRuleRepository.findByUserAndMerchant(user, key)
				.orElseGet(() -> MerchantRule.builder()
						.user(user)
						.merchant(key)
						.createdAt(now)
						.build());
		rule.setCategory(category);
		rule.setUpdatedAt(now);
		merchantRuleRepository.save(rule);
	}

	@Transactional
	public void forgetMerchantRule(String description, User user) {
		String key = merchantKey(description);
		if (key == null) {
			return;
		}
		merchantRuleRepository.deleteByUserAndMerchant(user, key);
	}

	@Transactional(readOnly = true)
	public List<MerchantRule> merchantRules(User user) {
		return merchantRuleRepository.findAllByUser(user).stream()
				.sorted(Comparator.comparing(MerchantRule::getMerchant))
				.toList();
	}

	/**
	 * The lookup key for a free-text expense description.
	 *
	 * <p>Quick-add descriptions carry the amount with the merchant — "Jollibee 150", "jollibee
	 * ₱200.50" — so the numbers have to come out or the same shop would never match itself twice.
	 * Lower-cases, drops currency symbols and standalone number tokens, strips punctuation and
	 * collapses whitespace. Returns {@code null} when nothing recognisable survives, which callers
	 * read as "no merchant here, do not learn and do not match".
	 */
	public static String merchantKey(String description) {
		if (description == null) {
			return null;
		}
		String key = description.toLowerCase(Locale.ROOT)
				.replaceAll("[₱$€£]", " ")
				// Only whitespace-delimited number tokens: "Jollibee 150" loses the 150, but the 7
				// in "7-Eleven" is glued to letters and is part of the name, so it stays.
				.replaceAll("(?:^|(?<=\\s))\\d[\\d,.]*(?=\\s|$)", " ")
				.replaceAll("[^\\p{L}\\p{N}\\s]", " ")
				.replaceAll("\\s+", " ")
				.trim();
		if (key.isEmpty()) {
			return null;
		}
		return key.length() > MAX_MERCHANT_KEY ? key.substring(0, MAX_MERCHANT_KEY).trim() : key;
	}

	private static String aliasKey(String alias) {
		String key = alias.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
		return key.length() > MAX_ALIAS ? key.substring(0, MAX_ALIAS).trim() : key;
	}

	private CategoryResponse toResponse(Category c) {
		return new CategoryResponse(c.getId(), c.getName(), c.getIcon(), c.getBucket());
	}
}
