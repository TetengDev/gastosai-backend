package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.query.AnalyticsQueryPlan;
import com.teng.app.gastosai.ai.query.AnalyticsQueryPlanner;
import com.teng.app.gastosai.ai.query.DateRange;
import com.teng.app.gastosai.ai.query.Metric;
import com.teng.app.gastosai.ai.query.QueryIntent;
import com.teng.app.gastosai.ai.query.SafeAnalyticsExecutor;
import com.teng.app.gastosai.ai.query.SortDirection;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the structured analytics pipeline only ever returns the querying user's own rows. */
@SpringBootTest
class AnalyticsQueryIsolationTest {

    @Autowired UserRepository userRepository;
    @Autowired ExpenseRepository expenseRepository;
    @Autowired AnalyticsQueryPlanner planner;
    @Autowired SafeAnalyticsExecutor executor;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        expenseRepository.deleteAll();
        userRepository.deleteAll();
        alice = userRepository.save(User.builder().name("Alice").email("alice@test.com").password("x").build());
        bob = userRepository.save(User.builder().name("Bob").email("bob@test.com").password("x").build());
        saveExpense(alice, "100.00");
        saveExpense(alice, "200.00");
        saveExpense(bob, "999.00");
    }

    private void saveExpense(User user, String amount) {
        expenseRepository.save(Expense.builder()
                .user(user)
                .amount(new BigDecimal(amount))
                .amountInBaseCurrency(new BigDecimal(amount))
                .description("test")
                .date(LocalDateTime.now())
                .build());
    }

    @Test
    void totalIsScopedToQueryingUser() {
        BigDecimal aliceTotal = total(alice.getId());
        BigDecimal bobTotal = total(bob.getId());

        assertThat(aliceTotal).isEqualByComparingTo("300.00");
        assertThat(bobTotal).isEqualByComparingTo("999.00");
    }

    private BigDecimal total(long userId) {
        AnalyticsQueryPlan plan = planner.build(
                new QueryIntent(Metric.TOTAL, DateRange.ALL, null, SortDirection.DESC, 10), userId);
        List<Map<String, Object>> rows = executor.run(plan);
        return new BigDecimal(rows.get(0).get("total").toString());
    }
}
