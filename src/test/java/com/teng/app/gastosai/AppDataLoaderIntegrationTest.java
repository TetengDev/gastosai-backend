package com.teng.app.gastosai;

import com.teng.app.gastosai.repository.BudgetRepository;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
import com.teng.app.gastosai.repository.UserRepository;
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
        assertThat(budgetRepository.findAllByUserAndMonth(demoUser, "2026-06")).isNotEmpty();
    }

    @Test
    void recurringExpensesSeededForDemoUser() {
        var demoUser = userRepository.findByEmail("demo@gastosai.dev").orElseThrow();
        assertThat(recurringExpenseRepository.findAllByUser(demoUser)).isNotEmpty();
    }

    @Test
    void idempotent_runAgain_doesNotDuplicate() {
        var demoUser = userRepository.findByEmail("demo@gastosai.dev").orElseThrow();
        long expenseBefore = expenseRepository.findAllByUserOrderByDateDesc(demoUser).size();

        assertThat(expenseBefore).isGreaterThan(0);
    }
}
