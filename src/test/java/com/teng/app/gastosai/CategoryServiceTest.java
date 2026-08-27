package com.teng.app.gastosai;

import com.teng.app.gastosai.config.CategoryLimitProperties;
import com.teng.app.gastosai.config.MonetizationProperties;
import com.teng.app.gastosai.dto.CategoryRequest;
import com.teng.app.gastosai.dto.CategoryResponse;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.CategoryAlias;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.MerchantRule;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.FeatureLockedException;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.CategoryAliasRepository;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.MerchantRuleRepository;
import com.teng.app.gastosai.service.CategoryService;
import com.teng.app.gastosai.service.EntitlementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    CategoryRepository categoryRepository;

    @Mock
    ExpenseRepository expenseRepository;

    @Mock
    MerchantRuleRepository merchantRuleRepository;

    @Mock
    CategoryAliasRepository categoryAliasRepository;

    @Mock
    MonetizationProperties monetizationProperties;

    @Mock
    CategoryLimitProperties categoryLimits;

    @Mock
    EntitlementService entitlementService;

    @InjectMocks
    CategoryService categoryService;

    private User testUser() {
        return User.builder().id(1L).email("u@test.com").name("Test").password("pw").role(Role.USER).build();
    }

    @Test
    void create_happyPath_savesAndReturnsResponse() {
        User user = testUser();
        when(categoryRepository.existsByUserAndNameIgnoreCase(user, "Food")).thenReturn(false);
        Category saved = Category.builder().id(1L).name("Food").icon("utensils").user(user).build();
        when(categoryRepository.save(any(Category.class))).thenReturn(saved);

        CategoryResponse response = categoryService.create(new CategoryRequest("Food", "utensils"), user);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("Food");
        assertThat(response.icon()).isEqualTo("utensils");
    }

    @Test
    void create_blockedAtPlanCategoryCap_whenEnforced() {
        User user = testUser();
        when(categoryRepository.existsByUserAndNameIgnoreCase(user, "Sixth")).thenReturn(false);
        when(monetizationProperties.isEnforce()).thenReturn(true);
        when(entitlementService.describe(user)).thenReturn(new EntitlementService.Entitlements(
                PlanKey.FREE, SubscriptionStatus.ACTIVE, EnumSet.noneOf(FeatureKey.class), false));
        when(categoryLimits.getFree()).thenReturn(5);
        when(categoryRepository.countByUser(user)).thenReturn(5L);

        assertThatThrownBy(() -> categoryService.create(new CategoryRequest("Sixth", null), user))
                .isInstanceOf(FeatureLockedException.class)
                .hasMessageContaining("limited to 5");
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void create_allowed_whenEnforcementOff() {
        User user = testUser();
        when(categoryRepository.existsByUserAndNameIgnoreCase(user, "Food")).thenReturn(false);
        when(monetizationProperties.isEnforce()).thenReturn(false);
        Category saved = Category.builder().id(1L).name("Food").user(user).build();
        when(categoryRepository.save(any(Category.class))).thenReturn(saved);

        CategoryResponse response = categoryService.create(new CategoryRequest("Food", null), user);

        assertThat(response.name()).isEqualTo("Food");
    }

    @Test
    void create_throwsIllegalArgument_whenNameAlreadyExists() {
        User user = testUser();
        when(categoryRepository.existsByUserAndNameIgnoreCase(user, "Food")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.create(new CategoryRequest("Food", null), user))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Food");
    }

    @Test
    void getOrCreateByName_returnsExisting_whenFound() {
        User user = testUser();
        Category existing = Category.builder().id(5L).name("Meal Plan").user(user).build();
        when(categoryRepository.findByUserAndNameIgnoreCase(user, "Meal Plan")).thenReturn(Optional.of(existing));

        Category result = categoryService.getOrCreateByName("Meal Plan", user);

        assertThat(result.getId()).isEqualTo(5L);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void getOrCreateByName_savesNew_whenNotFound() {
        User user = testUser();
        when(categoryRepository.findByUserAndNameIgnoreCase(user, "NewCat")).thenReturn(Optional.empty());
        Category saved = Category.builder().id(10L).name("NewCat").user(user).build();
        when(categoryRepository.save(any(Category.class))).thenReturn(saved);

        Category result = categoryService.getOrCreateByName("NewCat", user);

        assertThat(result.getId()).isEqualTo(10L);
        verify(categoryRepository).save(argThat(c -> "NewCat".equals(c.getName())));
    }

    @Test
    void delete_reassignExpensesToUncategorized_whenCategoryHasExpenses() {
        User user = testUser();
        Category toDelete = Category.builder().id(2L).name("Food").user(user).build();
        when(categoryRepository.findByIdAndUser(2L, user)).thenReturn(Optional.of(toDelete));

        Expense expense = Expense.builder()
                .id(1L)
                .amount(new BigDecimal("100.00"))
                .category(toDelete)
                .description("Lunch")
                .build();
        when(expenseRepository.findByCategory_IdAndUser(2L, user)).thenReturn(List.of(expense));

        Category uncategorized = Category.builder().id(99L).name("Uncategorized").user(user).build();
        when(categoryRepository.findByUserAndNameIgnoreCase(user, "Uncategorized")).thenReturn(Optional.of(uncategorized));

        categoryService.delete(2L, user);

        assertThat(expense.getCategory().getId()).isEqualTo(99L);
        verify(expenseRepository).saveAll(List.of(expense));
        verify(categoryRepository).deleteById(2L);
    }

    @Test
    void delete_deletesDirectly_whenCategoryHasNoExpenses() {
        User user = testUser();
        Category toDelete = Category.builder().id(3L).name("Snacks").user(user).build();
        when(categoryRepository.findByIdAndUser(3L, user)).thenReturn(Optional.of(toDelete));
        when(expenseRepository.findByCategory_IdAndUser(3L, user)).thenReturn(List.of());

        categoryService.delete(3L, user);

        verify(expenseRepository, never()).saveAll(any());
        verify(categoryRepository).deleteById(3L);
    }

    @Test
    void delete_defaultCategory_throws() {
        User user = testUser();
        Category def = Category.builder().id(4L).name("Uncategorized").user(user).build();
        when(categoryRepository.findByIdAndUser(4L, user)).thenReturn(Optional.of(def));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> categoryService.delete(4L, user));
        verify(categoryRepository, never()).deleteById(any());
    }

    // ------------------------------------------------------------ merchantKey

    @Test
    void merchantKey_stripsAmountsSoTheSameShopMatchesItself() {
        assertThat(CategoryService.merchantKey("Jollibee 150")).isEqualTo("jollibee");
        assertThat(CategoryService.merchantKey("jollibee ₱200.50")).isEqualTo("jollibee");
        assertThat(CategoryService.merchantKey("  JOLLIBEE  ")).isEqualTo("jollibee");
        assertThat(CategoryService.merchantKey("7-Eleven 89")).isEqualTo("7 eleven");
    }

    @Test
    void merchantKey_returnsNull_whenNothingRecognisableSurvives() {
        assertThat(CategoryService.merchantKey(null)).isNull();
        assertThat(CategoryService.merchantKey("   ")).isNull();
        assertThat(CategoryService.merchantKey("450.00")).isNull();
        assertThat(CategoryService.merchantKey("--- ₱₱ ---")).isNull();
    }

    // -------------------------------------------------------- merchant rules

    @Test
    void resolveByMerchant_returnsRuleCategory_onExactKey() {
        User user = testUser();
        Category food = Category.builder().id(7L).name("Food").user(user).build();
        when(merchantRuleRepository.findByUserAndMerchant(user, "jollibee"))
                .thenReturn(Optional.of(MerchantRule.builder().id(1L).user(user)
                        .merchant("jollibee").category(food).build()));

        assertThat(categoryService.resolveByMerchant("Jollibee 150", user))
                .get().extracting(Category::getId).isEqualTo(7L);
    }

    @Test
    void resolveByMerchant_fallsBackToLongestPrefixRule_onWordBoundary() {
        User user = testUser();
        Category food = Category.builder().id(7L).name("Food").user(user).build();
        Category coffee = Category.builder().id(8L).name("Coffee").user(user).build();
        when(merchantRuleRepository.findByUserAndMerchant(user, "starbucks reserve roastery"))
                .thenReturn(Optional.empty());
        when(merchantRuleRepository.findAllByUser(user)).thenReturn(List.of(
                MerchantRule.builder().id(1L).user(user).merchant("starbucks").category(food).build(),
                MerchantRule.builder().id(2L).user(user).merchant("starbucks reserve").category(coffee).build()));

        assertThat(categoryService.resolveByMerchant("Starbucks Reserve Roastery 480", user))
                .get().extracting(Category::getId).isEqualTo(8L);
    }

    @Test
    void resolveByMerchant_doesNotMatchMidWord() {
        User user = testUser();
        Category food = Category.builder().id(7L).name("Food").user(user).build();
        when(merchantRuleRepository.findByUserAndMerchant(user, "jollibeef")).thenReturn(Optional.empty());
        when(merchantRuleRepository.findAllByUser(user)).thenReturn(List.of(
                MerchantRule.builder().id(1L).user(user).merchant("jollibee").category(food).build()));

        assertThat(categoryService.resolveByMerchant("Jollibeef 90", user)).isEmpty();
    }

    @Test
    void resolveByMerchant_isEmpty_whenDescriptionHasNoMerchant() {
        assertThat(categoryService.resolveByMerchant("500", testUser())).isEmpty();
        verify(merchantRuleRepository, never()).findByUserAndMerchant(any(), any());
    }

    @Test
    void learnMerchantRule_savesNewRule_forTheNormalisedKey() {
        User user = testUser();
        Category food = Category.builder().id(7L).name("Food").user(user).build();
        when(merchantRuleRepository.findByUserAndMerchant(user, "jollibee")).thenReturn(Optional.empty());

        categoryService.learnMerchantRule("Jollibee 150", food, user);

        verify(merchantRuleRepository).save(argThat(r ->
                "jollibee".equals(r.getMerchant())
                        && r.getCategory().getId().equals(7L)
                        && r.getCreatedAt() != null
                        && r.getUpdatedAt() != null));
    }

    @Test
    void learnMerchantRule_movesExistingRule_ratherThanDuplicatingIt() {
        User user = testUser();
        Category food = Category.builder().id(7L).name("Food").user(user).build();
        Category treats = Category.builder().id(9L).name("Treats").user(user).build();
        MerchantRule existing = MerchantRule.builder().id(1L).user(user)
                .merchant("jollibee").category(food).build();
        when(merchantRuleRepository.findByUserAndMerchant(user, "jollibee")).thenReturn(Optional.of(existing));

        categoryService.learnMerchantRule("Jollibee", treats, user);

        verify(merchantRuleRepository).save(argThat(r ->
                r.getId().equals(1L) && r.getCategory().getId().equals(9L)));
    }

    @Test
    void learnMerchantRule_ignoresUncategorized_becauseNotSayingIsNotARule() {
        User user = testUser();
        Category uncategorized = Category.builder().id(1L).name("Uncategorized").user(user).build();

        categoryService.learnMerchantRule("Jollibee 150", uncategorized, user);

        verify(merchantRuleRepository, never()).save(any());
    }

    @Test
    void learnMerchantRule_ignoresDescriptionWithNoMerchant() {
        categoryService.learnMerchantRule("450.00", Category.builder().id(7L).name("Food").build(), testUser());

        verify(merchantRuleRepository, never()).save(any());
    }

    @Test
    void delete_removesRulesAndAliasesPointingAtTheCategory() {
        User user = testUser();
        Category toDelete = Category.builder().id(2L).name("Food").user(user).build();
        when(categoryRepository.findByIdAndUser(2L, user)).thenReturn(Optional.of(toDelete));
        when(expenseRepository.findByCategory_IdAndUser(2L, user)).thenReturn(List.of());
        MerchantRule rule = MerchantRule.builder().id(1L).user(user).merchant("jollibee").category(toDelete).build();
        CategoryAlias alias = CategoryAlias.builder().id(3L).user(user).category(toDelete).alias("pagkain").build();
        when(merchantRuleRepository.findAllByUserAndCategory_Id(user, 2L)).thenReturn(List.of(rule));
        when(categoryAliasRepository.findAllByUserAndCategory_Id(user, 2L)).thenReturn(List.of(alias));

        categoryService.delete(2L, user);

        verify(merchantRuleRepository).deleteAll(List.of(rule));
        verify(categoryAliasRepository).deleteAll(List.of(alias));
    }

    // --------------------------------------------------------------- aliases

    @Test
    void resolveByName_prefersARealCategoryName_overAnAlias() {
        User user = testUser();
        Category food = Category.builder().id(7L).name("Food").user(user).build();
        when(categoryRepository.findByUserAndNameIgnoreCase(user, "Food")).thenReturn(Optional.of(food));

        assertThat(categoryService.resolveByName("Food", user))
                .get().extracting(Category::getId).isEqualTo(7L);
        verify(categoryAliasRepository, never()).findByUserAndAlias(any(), any());
    }

    @Test
    void resolveByName_fallsBackToAnAlias_whenNoCategoryHasThatName() {
        User user = testUser();
        Category groceries = Category.builder().id(4L).name("Groceries").user(user).build();
        when(categoryRepository.findByUserAndNameIgnoreCase(user, "Palengke")).thenReturn(Optional.empty());
        when(categoryAliasRepository.findByUserAndAlias(user, "palengke")).thenReturn(
                Optional.of(CategoryAlias.builder().id(1L).user(user).category(groceries).alias("palengke").build()));

        assertThat(categoryService.resolveByName("Palengke", user))
                .get().extracting(Category::getId).isEqualTo(4L);
    }

    @Test
    void getOrCreateByName_returnsAliasedCategory_insteadOfCreatingADuplicate() {
        User user = testUser();
        Category groceries = Category.builder().id(4L).name("Groceries").user(user).build();
        when(categoryRepository.findByUserAndNameIgnoreCase(user, "grocery")).thenReturn(Optional.empty());
        when(categoryAliasRepository.findByUserAndAlias(user, "grocery")).thenReturn(
                Optional.of(CategoryAlias.builder().id(1L).user(user).category(groceries).alias("grocery").build()));

        Category result = categoryService.getOrCreateByName("grocery", user);

        assertThat(result.getId()).isEqualTo(4L);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void addAlias_savesNormalisedAlias() {
        User user = testUser();
        Category groceries = Category.builder().id(4L).name("Groceries").user(user).build();
        when(categoryRepository.findByIdAndUser(4L, user)).thenReturn(Optional.of(groceries));
        when(categoryRepository.findByUserAndNameIgnoreCase(user, "palengke")).thenReturn(Optional.empty());
        when(categoryAliasRepository.findByUserAndAlias(user, "palengke")).thenReturn(Optional.empty());

        categoryService.addAlias(4L, "  Palengke  ", user);

        verify(categoryAliasRepository).save(argThat(a ->
                "palengke".equals(a.getAlias()) && a.getCategory().getId().equals(4L)));
    }

    @Test
    void addAlias_rejects_whenAliasAlreadyNamesAnotherCategory() {
        User user = testUser();
        Category groceries = Category.builder().id(4L).name("Groceries").user(user).build();
        Category food = Category.builder().id(7L).name("food").user(user).build();
        when(categoryRepository.findByIdAndUser(4L, user)).thenReturn(Optional.of(groceries));
        when(categoryRepository.findByUserAndNameIgnoreCase(user, "food")).thenReturn(Optional.of(food));

        assertThatThrownBy(() -> categoryService.addAlias(4L, "Food", user))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already names a category");
        verify(categoryAliasRepository, never()).save(any());
    }

    @Test
    void addAlias_rejects_whenAliasBelongsToAnotherCategory() {
        User user = testUser();
        Category groceries = Category.builder().id(4L).name("Groceries").user(user).build();
        Category food = Category.builder().id(7L).name("Food").user(user).build();
        when(categoryRepository.findByIdAndUser(4L, user)).thenReturn(Optional.of(groceries));
        when(categoryRepository.findByUserAndNameIgnoreCase(user, "kain")).thenReturn(Optional.empty());
        when(categoryAliasRepository.findByUserAndAlias(user, "kain")).thenReturn(
                Optional.of(CategoryAlias.builder().id(1L).user(user).category(food).alias("kain").build()));

        assertThatThrownBy(() -> categoryService.addAlias(4L, "kain", user))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another category");
        verify(categoryAliasRepository, never()).save(any());
    }

    @Test
    void addAlias_isNoOp_whenAliasIsTheCategorysOwnName() {
        User user = testUser();
        Category groceries = Category.builder().id(4L).name("Groceries").user(user).build();
        when(categoryRepository.findByIdAndUser(4L, user)).thenReturn(Optional.of(groceries));

        categoryService.addAlias(4L, "groceries", user);

        verify(categoryAliasRepository, never()).save(any());
    }

    @Test
    void addAlias_throwsNotFound_whenCategoryIsNotTheUsers() {
        User user = testUser();
        when(categoryRepository.findByIdAndUser(99L, user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.addAlias(99L, "palengke", user))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aliasesFor_returnsSortedAliases() {
        User user = testUser();
        Category groceries = Category.builder().id(4L).name("Groceries").user(user).build();
        when(categoryRepository.findByIdAndUser(4L, user)).thenReturn(Optional.of(groceries));
        when(categoryAliasRepository.findAllByUserAndCategory_Id(user, 4L)).thenReturn(List.of(
                CategoryAlias.builder().id(1L).user(user).category(groceries).alias("palengke").build(),
                CategoryAlias.builder().id(2L).user(user).category(groceries).alias("grocery").build()));

        assertThat(categoryService.aliasesFor(4L, user)).containsExactly("grocery", "palengke");
    }
}
