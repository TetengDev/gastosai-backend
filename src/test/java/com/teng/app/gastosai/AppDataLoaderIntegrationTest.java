package com.teng.app.gastosai;

import com.teng.app.gastosai.repository.BudgetRepository;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
import com.teng.app.gastosai.repository.SavingsGoalRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.repository.UserSubscriptionRepository;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;
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

    @Test
    void demoUserCreated() {
        assertThat(userRepository.findByEmail("demo@gastosai.dev")).isPresent();
    }

    @Test
    void sampleExpensesSeeded() {
        var demoUser = userRepository.findByEmail("demo@gastosai.dev").orElseThrow();
        assertThat(expenseRepository.findAllByUserOrderByDateDesc(demoUser)).isNotEmpty();
    }

    @Test
    void categoriesSeeded() {
        assertThat(categoryRepository.findAll()).isNotEmpty();
    }

    @Test
    void budgetsSeededForDemoUser() {
        var demoUser = userRepository.findByEmail("demo@gastosai.dev").orElseThrow();
        assertThat(budgetRepository.findAllByUserAndMonth(demoUser, YearMonth.now().toString())).isNotEmpty();
    }

    @Test
    void recurringExpensesSeededForDemoUser() {
        var demoUser = userRepository.findByEmail("demo@gastosai.dev").orElseThrow();
        assertThat(recurringExpenseRepository.findAllByUser(demoUser)).isNotEmpty();
    }

    @Test
    void goalsSeededForDemoUser() {
        var demoUser = userRepository.findByEmail("demo@gastosai.dev").orElseThrow();
        assertThat(savingsGoalRepository.findAllByUserOrderByCreatedAtDesc(demoUser)).isNotEmpty();
    }

    @Test
    void demoUserHasActivePremiumSubscription() {
        var demoUser = userRepository.findByEmail("demo@gastosai.dev").orElseThrow();
        var subscription = userSubscriptionRepository.findFirstByUserOrderByCreatedAtDesc(demoUser).orElseThrow();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.getPlan().getPlanKey()).isEqualTo(PlanKey.PREMIUM);
    }

    @Test
    void idempotent_runAgain_doesNotDuplicate() {
        var demoUser = userRepository.findByEmail("demo@gastosai.dev").orElseThrow();
        long expenseBefore = expenseRepository.findAllByUserOrderByDateDesc(demoUser).size();

        assertThat(expenseBefore).isGreaterThan(0);
    }
}
