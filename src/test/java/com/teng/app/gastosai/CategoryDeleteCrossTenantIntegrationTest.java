package com.teng.app.gastosai;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.CategoryService;
import com.teng.app.gastosai.support.PostgresBackedTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deleting a category must never write to an expense belonging to another user (TEN-318).
 *
 * <p>Not {@code @Transactional}: the first test asserts what survives a rolled-back service call,
 * which a test-owned transaction would hide.
 */
@SpringBootTest
class CategoryDeleteCrossTenantIntegrationTest extends PostgresBackedTest {

    @Autowired UserRepository userRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ExpenseRepository expenseRepository;
    @Autowired CategoryService categoryService;

    User alice;
    User bob;

    @BeforeEach
    void setUp() {
        alice = userRepository.save(User.builder().name("Alice").email("alice-318@test.com").password("pw").build());
        bob = userRepository.save(User.builder().name("Bob").email("bob-318@test.com").password("pw").build());
    }

    /**
     * The legacy shape V29 repairs: Alice's expense carries Bob's category, written before TEN-314
     * scoped resolution to the expense's owner. Bob deleting that category used to reassign Alice's
     * row to a fallback of Bob's — a fresh cross-tenant link, invisible to her.
     *
     * <p>With the lookup scoped to the owner, Bob's delete leaves the row alone; the foreign key
     * then refuses the delete because Alice's expense still references the category, and the whole
     * transaction rolls back. Loud refusal beats silently rewriting another tenant's row, and the
     * state is unreachable once V29 has run.
     */
    @Test
    void deletingACategory_neverTouchesAnotherUsersExpense() {
        Category bobsFood = categoryRepository.save(Category.builder().name("Food").user(bob).build());
        Expense alicesExpense = expenseRepository.save(expense(alice, bobsFood, "Alice lunch"));
        Expense bobsExpense = expenseRepository.save(expense(bob, bobsFood, "Bob lunch"));

        assertThatThrownBy(() -> categoryService.delete(bobsFood.getId(), bob))
                .isInstanceOf(DataIntegrityViolationException.class);

        Expense reloaded = expenseRepository.findById(alicesExpense.getId()).orElseThrow();
        assertThat(reloaded.getUser().getId()).isEqualTo(alice.getId());
        assertThat(reloaded.getCategory().getId())
                .as("Alice's expense must still carry the category it had before Bob's delete")
                .isEqualTo(bobsFood.getId());
        assertThat(reloaded.getDescription()).isEqualTo("Alice lunch");

        // The rollback covers Bob's own row too — his delete failed, so nothing moved.
        assertThat(expenseRepository.findById(bobsExpense.getId()).orElseThrow().getCategory().getId())
                .isEqualTo(bobsFood.getId());
        assertThat(categoryRepository.findById(bobsFood.getId())).isPresent();
    }

    /** The ordinary case still works: Bob's own expenses fall back to Bob's Uncategorized. */
    @Test
    void deletingACategory_reassignsOnlyTheOwnersExpenses() {
        Category alicesFood = categoryRepository.save(Category.builder().name("Food").user(alice).build());
        Category bobsFood = categoryRepository.save(Category.builder().name("Food").user(bob).build());
        Expense alicesExpense = expenseRepository.save(expense(alice, alicesFood, "Alice lunch"));
        Expense bobsExpense = expenseRepository.save(expense(bob, bobsFood, "Bob lunch"));

        categoryService.delete(bobsFood.getId(), bob);

        Category bobsFallback = expenseRepository.findById(bobsExpense.getId()).orElseThrow().getCategory();
        assertThat(bobsFallback.getName()).isEqualTo("Uncategorized");
        assertThat(bobsFallback.getUser().getId()).isEqualTo(bob.getId());

        Expense reloadedAlice = expenseRepository.findById(alicesExpense.getId()).orElseThrow();
        assertThat(reloadedAlice.getCategory().getId()).isEqualTo(alicesFood.getId());
        assertThat(categoryRepository.findById(alicesFood.getId())).isPresent();
    }

    private Expense expense(User owner, Category category, String description) {
        return Expense.builder()
                .user(owner)
                .category(category)
                .description(description)
                .amount(new BigDecimal("100.0000"))
                .amountInBaseCurrency(new BigDecimal("100.0000"))
                .build();
    }
}
