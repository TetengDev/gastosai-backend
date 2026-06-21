package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.dto.AiQueryResponse;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.AiQueryService;
import com.teng.app.gastosai.service.AiQuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * F-06 regression: proves the NL→SQL <em>fallback</em> path (AI-authored SQL, guarded by SqlGuard
 * and scoped by {@code appendUserFilter}) never returns another user's rows — even when the model
 * emits adversarial SQL that selects across all users. Complements {@link AnalyticsQueryIsolationTest}
 * which covers the structured path.
 */
@SpringBootTest
class AiQueryFallbackTenantIsolationTest {

    @Autowired UserRepository userRepository;
    @Autowired ExpenseRepository expenseRepository;
    @Autowired AiQueryService aiQueryService;

    // Force the fallback path and feed it adversarial, cross-user SQL.
    @MockitoBean SqlGenerator sqlGenerator;
    // Bypass quota gating so the call reaches the fallback executor.
    @MockitoBean AiQuotaService aiQuotaService;

    private User alice;

    @BeforeEach
    void setUp() {
        expenseRepository.deleteAll();
        userRepository.deleteAll();
        alice = userRepository.save(User.builder().name("Alice").email("alice@test.com").password("x").build());
        User bob = userRepository.save(User.builder().name("Bob").email("bob@test.com").password("x").build());
        saveExpense(alice, "11.11");
        saveExpense(alice, "22.22");
        saveExpense(bob, "99.99");

        // Skip the structured path → exercise the guarded fallback.
        lenient().when(sqlGenerator.classifyQueryIntentJson(anyString())).thenReturn(null);
        // Summary echoes the serialized rows so the response carries the data we assert on.
        lenient().when(sqlGenerator.generateSummary(anyString(), anyString(), anyString()))
                .thenAnswer(i -> i.getArgument(1));
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
    void flatSelectAcrossAllUsers_returnsOnlyQueryingUserRows() {
        when(sqlGenerator.generateSql(anyString()))
                .thenReturn("SELECT amount FROM expenses ORDER BY amount DESC");

        AiQueryResponse r = aiQueryService.runNaturalLanguageQuery("everything", "plain", alice);
        String answer = String.valueOf(r.answer());

        assertThat(answer).contains("11.11").contains("22.22");
        assertThat(answer).doesNotContain("99.99");
    }

    @Test
    void groupByAcrossAllUsers_aggregatesOnlyQueryingUserRows() {
        when(sqlGenerator.generateSql(anyString()))
                .thenReturn("SELECT category_id, SUM(amount) AS total FROM expenses GROUP BY category_id");

        AiQueryResponse r = aiQueryService.runNaturalLanguageQuery("totals", "plain", alice);
        String answer = String.valueOf(r.answer());

        // Alice's two rows sum to 33.33; Bob's 99.99 must never appear.
        assertThat(answer).contains("33.33");
        assertThat(answer).doesNotContain("99.99");
    }
}
