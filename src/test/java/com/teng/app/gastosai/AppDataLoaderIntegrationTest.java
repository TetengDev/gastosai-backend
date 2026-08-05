package com.teng.app.gastosai;

import com.teng.app.gastosai.bootstrap.AppDataLoader;
import com.teng.app.gastosai.config.JacksonTimeConfig;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.BudgetRepository;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
import com.teng.app.gastosai.repository.SavingsGoalRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.repository.UserSubscriptionRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "gastos.seed-sample-data=true",
        "spring.datasource.url=jdbc:h2:mem:seedtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AppDataLoaderIntegrationTest {

    private static final String DEMO_EMAIL = "demo@gastosai.dev";

    @Autowired
    UserRepository userRepository;

    @Autowired
    ExpenseRepository expenseRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    BudgetRepository budgetRepository;

    @Autowired
    RecurringExpenseRepository recurringExpenseRepository;

    @Autowired
    SavingsGoalRepository savingsGoalRepository;

    @Autowired
    UserSubscriptionRepository userSubscriptionRepository;

    @Autowired
    AppDataLoader appDataLoader;

    private User user(String email) {
        return userRepository.findByEmail(email).orElseThrow();
    }

    private static YearMonth currentMonth() {
        return YearMonth.from(LocalDate.now(JacksonTimeConfig.APP_ZONE));
    }

    @Test
    void demoUserCreated() {
        assertThat(userRepository.findByEmail(DEMO_EMAIL)).isPresent();
    }

    @Test
    void sampleExpensesSeeded() {
        assertThat(expenseRepository.findAllByUserOrderByDateDesc(user(DEMO_EMAIL))).isNotEmpty();
    }

    @Test
    void categoriesSeeded() {
        assertThat(categoryRepository.findAll()).isNotEmpty();
    }

    /**
     * The regression guard for the whole issue: the seeded months are generated relative to today,
     * so this is the assertion that fails if the set ever goes back to fixed calendar dates. Every
     * client's dashboard, daily trend and top-expenses card queries the <em>current</em> month, so
     * an empty current month is an empty set of cards however many rows the database holds.
     */
    @Test
    void currentMonthIsNonEmptyForDemoUser() {
        YearMonth month = currentMonth();
        var currentMonthExpenses = expenseRepository.findAllByUserOrderByDateDesc(user(DEMO_EMAIL)).stream()
                .filter(e -> YearMonth.from(e.getDate()).equals(month))
                .toList();

        assertThat(currentMonthExpenses)
                .as("expenses in the current month (%s) — the dashboard, daily trend and "
                        + "top-expenses cards all render from this window", month)
                .isNotEmpty();
    }

    /** Month-over-month comparison and the trend line need more than one distinct month. */
    @Test
    void expensesSpanSeveralDistinctMonths() {
        Set<YearMonth> months = expenseRepository.findAllByUserOrderByDateDesc(user(DEMO_EMAIL)).stream()
                .map(e -> YearMonth.from(e.getDate()))
                .collect(Collectors.toSet());

        assertThat(months).as("distinct months covered by the demo set").hasSizeGreaterThanOrEqualTo(3);
        assertThat(months).contains(currentMonth(), currentMonth().minusMonths(1));
    }

    /** A demo that opens with future-dated spending misreports every total on the dashboard. */
    @Test
    void noSeededExpenseIsDatedInTheFuture() {
        LocalDateTime now = LocalDateTime.now(JacksonTimeConfig.APP_ZONE);
        assertThat(expenseRepository.findAllByUserOrderByDateDesc(user(DEMO_EMAIL)))
                .allSatisfy(e -> assertThat(e.getDate()).isBeforeOrEqualTo(now));
    }

    @Test
    void budgetsSeededForDemoUser() {
        assertThat(budgetRepository.findAllByUserAndMonth(user(DEMO_EMAIL), currentMonth().toString())).isNotEmpty();
    }

    /** Budgets cover the earlier months too, so no past month's budget view opens empty. */
    @Test
    void budgetsSeededForEarlierMonthsToo() {
        User demoUser = user(DEMO_EMAIL);
        assertThat(budgetRepository.findAllByUserAndMonth(demoUser, currentMonth().minusMonths(1).toString()))
                .isNotEmpty();
        assertThat(budgetRepository.findAllByUserAndMonth(demoUser, currentMonth().minusMonths(2).toString()))
                .isNotEmpty();
    }

    @Test
    void seededBudgetsHaveBaseCurrencyAmountSet() {
        var budgets = budgetRepository.findAllByUserAndMonth(user(DEMO_EMAIL), currentMonth().toString());
        assertThat(budgets).isNotEmpty();
        assertThat(budgets).allSatisfy(b -> {
            assertThat(b.getAmountLimitInBaseCurrency()).isNotNull();
            assertThat(b.getAmountLimitInBaseCurrency()).isEqualByComparingTo(b.getAmountLimit());
            assertThat(b.getAmountLimitInBaseCurrency().signum()).isPositive();
        });
    }

    @Test
    void recurringExpensesSeededForDemoUser() {
        assertThat(recurringExpenseRepository.findAllByUser(user(DEMO_EMAIL))).isNotEmpty();
    }

    @Test
    void goalsSeededForDemoUser() {
        assertThat(savingsGoalRepository.findAllByUserOrderByCreatedAtDesc(user(DEMO_EMAIL))).isNotEmpty();
    }

    /** Goal target dates are relative, so a demo never opens on a set of already-overdue goals. */
    @Test
    void seededGoalsTargetTheFuture() {
        LocalDate today = LocalDate.now(JacksonTimeConfig.APP_ZONE);
        assertThat(savingsGoalRepository.findAllByUserOrderByCreatedAtDesc(user(DEMO_EMAIL)))
                .isNotEmpty()
                .allSatisfy(g -> assertThat(g.getTargetDate()).isAfter(today));
    }

    @Test
    void demoUserHasActivePremiumSubscription() {
        var subscription = userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(user(DEMO_EMAIL))
                .orElseThrow();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.getPlan().getPlanKey()).isEqualTo(PlanKey.PREMIUM);
    }

    /**
     * Each tier gets its own login so tier behaviour is exercisable without the admin "view as"
     * toggle — and each one needs the same data behind it, or the tier is only testable on
     * empty screens.
     */
    @ParameterizedTest
    @CsvSource({
            "free@gastosai.dev,    FREE,    ACTIVE",
            "premium@gastosai.dev, PREMIUM, ACTIVE",
            "trial@gastosai.dev,   TRIAL,   TRIAL"
    })
    void perTierUserSeededWithDataAndPlan(String email, PlanKey planKey, SubscriptionStatus status) {
        User tierUser = user(email);

        var subscription = userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(tierUser).orElseThrow();
        assertThat(subscription.getPlan().getPlanKey()).isEqualTo(planKey);
        assertThat(subscription.getStatus()).isEqualTo(status);

        assertThat(expenseRepository.findAllByUserOrderByDateDesc(tierUser)).isNotEmpty();
        assertThat(budgetRepository.findAllByUserAndMonth(tierUser, currentMonth().toString())).isNotEmpty();
        assertThat(savingsGoalRepository.findAllByUserOrderByCreatedAtDesc(tierUser)).isNotEmpty();
        assertThat(recurringExpenseRepository.findAllByUser(tierUser)).isNotEmpty();
    }

    /**
     * Idempotence is what makes the seed survive a restart: the loader runs on every boot, and
     * every guard it holds has to be a no-op the second time. This re-invokes the runner against
     * the already-seeded database rather than asserting on the first run's counts.
     */
    @Test
    void rerunningTheLoaderDoesNotDuplicateAnything() {
        User demoUser = user(DEMO_EMAIL);
        long usersBefore = userRepository.count();
        int expensesBefore = expenseRepository.findAllByUserOrderByDateDesc(demoUser).size();
        int budgetsBefore = budgetRepository.findAllByUserAndMonth(demoUser, currentMonth().toString()).size();
        int recurringBefore = recurringExpenseRepository.findAllByUser(demoUser).size();
        int goalsBefore = savingsGoalRepository.findAllByUserOrderByCreatedAtDesc(demoUser).size();
        long categoriesBefore = categoryRepository.count();

        assertThat(expensesBefore).isPositive();

        appDataLoader.run(null);

        assertThat(userRepository.count()).isEqualTo(usersBefore);
        assertThat(expenseRepository.findAllByUserOrderByDateDesc(demoUser)).hasSize(expensesBefore);
        assertThat(budgetRepository.findAllByUserAndMonth(demoUser, currentMonth().toString()))
                .hasSize(budgetsBefore);
        assertThat(recurringExpenseRepository.findAllByUser(demoUser)).hasSize(recurringBefore);
        assertThat(savingsGoalRepository.findAllByUserOrderByCreatedAtDesc(demoUser)).hasSize(goalsBefore);
        assertThat(categoryRepository.count()).isEqualTo(categoriesBefore);
        assertThat(userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(demoUser)).isPresent();
    }
}
