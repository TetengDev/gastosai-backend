package com.teng.app.gastosai;

import com.teng.app.gastosai.dto.BudgetRuleRequest;
import com.teng.app.gastosai.dto.BudgetRuleResponse;
import com.teng.app.gastosai.dto.BudgetRuleSummaryResponse;
import com.teng.app.gastosai.entity.Bucket;
import com.teng.app.gastosai.entity.BudgetRule;
import com.teng.app.gastosai.entity.BudgetRuleType;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.BudgetRuleRepository;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.service.BudgetRuleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BudgetRuleServiceTest {

    @Mock BudgetRuleRepository budgetRuleRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock ExpenseRepository expenseRepository;
    @InjectMocks BudgetRuleService service;

    private final User user = User.builder().id(1L).email("u@b.com").name("U").password("x").build();

    @Test
    void get_returnsDefault_whenNoRuleSet() {
        when(budgetRuleRepository.findByUser(user)).thenReturn(Optional.empty());

        BudgetRuleResponse r = service.get(user);

        assertThat(r.enabled()).isFalse();
        assertThat(r.ruleType()).isEqualTo(BudgetRuleType.FIFTY_THIRTY_TWENTY);
        assertThat(r.needsPct()).isEqualTo(50);
        assertThat(r.wantsPct()).isEqualTo(30);
        assertThat(r.savingsPct()).isEqualTo(20);
    }

    @Test
    void setEnabled_createsAndEnablesDefaultRule_whenNoneExists() {
        when(budgetRuleRepository.findByUser(user)).thenReturn(Optional.empty());
        when(budgetRuleRepository.save(any(BudgetRule.class))).thenAnswer(inv -> inv.getArgument(0));

        BudgetRuleResponse r = service.setEnabled(user, true);

        assertThat(r.enabled()).isTrue();
        assertThat(r.ruleType()).isEqualTo(BudgetRuleType.FIFTY_THIRTY_TWENTY);
    }

    @Test
    void upsert_preset_appliesPresetPercentages() {
        when(budgetRuleRepository.findByUser(user)).thenReturn(Optional.empty());
        when(budgetRuleRepository.save(any(BudgetRule.class))).thenAnswer(inv -> inv.getArgument(0));

        BudgetRuleResponse r = service.upsert(user, new BudgetRuleRequest(
                BudgetRuleType.SEVENTY_TWENTY_TEN, new BigDecimal("20000"), null, null, null));

        assertThat(r.needsPct()).isEqualTo(70);
        assertThat(r.wantsPct()).isEqualTo(20);
        assertThat(r.savingsPct()).isEqualTo(10);
    }

    @Test
    void upsert_custom_rejectsPercentagesNotSummingTo100() {
        assertThatThrownBy(() -> service.upsert(user, new BudgetRuleRequest(
                BudgetRuleType.CUSTOM, new BigDecimal("10000"), 50, 30, 30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sum to 100");
    }

    @Test
    void summary_aggregatesSpendByBucket_andTracksUnassigned() {
        BudgetRule rule = BudgetRule.builder()
                .user(user).ruleType(BudgetRuleType.FIFTY_THIRTY_TWENTY)
                .monthlyIncome(new BigDecimal("10000")).needsPct(50).wantsPct(30).savingsPct(20)
                .build();
        when(budgetRuleRepository.findByUser(user)).thenReturn(Optional.of(rule));
        when(categoryRepository.findAllByUser(user)).thenReturn(List.of(
                Category.builder().id(1L).name("Rent").bucket(Bucket.NEEDS).user(user).build(),
                Category.builder().id(2L).name("Dining").bucket(Bucket.WANTS).user(user).build(),
                Category.builder().id(3L).name("Misc").user(user).build())); // unassigned
        when(expenseRepository.sumByCategoryAndMonth(user, 2026, 6)).thenReturn(List.of(
                new Object[]{1L, new BigDecimal("3000")},
                new Object[]{2L, new BigDecimal("1000")},
                new Object[]{3L, new BigDecimal("500")}));

        BudgetRuleSummaryResponse s = service.summary(user, "2026-06");

        var needs = s.buckets().stream().filter(b -> b.bucket() == Bucket.NEEDS).findFirst().orElseThrow();
        assertThat(needs.target()).isEqualByComparingTo("5000.00");
        assertThat(needs.spent()).isEqualByComparingTo("3000.00");
        assertThat(needs.remaining()).isEqualByComparingTo("2000.00");
        var savings = s.buckets().stream().filter(b -> b.bucket() == Bucket.SAVINGS).findFirst().orElseThrow();
        assertThat(savings.target()).isEqualByComparingTo("2000.00");
        assertThat(savings.spent()).isEqualByComparingTo("0.00");
        assertThat(s.unassignedSpent()).isEqualByComparingTo("500.00");
    }

    /** Summary of a 50/30/20 rule where the NEEDS bucket (one category) has {@code needsSpent} against it. */
    private BudgetRuleSummaryResponse.BucketSummary needsBucket(String income, String needsSpent) {
        BudgetRule rule = BudgetRule.builder()
                .user(user).ruleType(BudgetRuleType.FIFTY_THIRTY_TWENTY)
                .monthlyIncome(new BigDecimal(income)).needsPct(50).wantsPct(30).savingsPct(20)
                .build();
        when(budgetRuleRepository.findByUser(user)).thenReturn(Optional.of(rule));
        when(categoryRepository.findAllByUser(user)).thenReturn(List.of(
                Category.builder().id(1L).name("Rent").bucket(Bucket.NEEDS).user(user).build()));
        List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(new Object[]{1L, new BigDecimal(needsSpent)});
        when(expenseRepository.sumByCategoryAndMonth(user, 2026, 6)).thenReturn(rows);
        return service.summary(user, "2026-06").buckets().stream()
                .filter(b -> b.bucket() == Bucket.NEEDS).findFirst().orElseThrow();
    }

    @Test
    void summary_bucketStatus_underTheWarningBand_isOnTrack() {
        assertThat(needsBucket("10000", "3999.50").status()).isEqualTo("ON_TRACK"); // 79.99%
    }

    @Test
    void summary_bucketStatus_atExactly80_isWarning() {
        assertThat(needsBucket("10000", "4000").status()).isEqualTo("WARNING");
    }

    @Test
    void summary_bucketStatus_insideTheWarningBand_isWarning() {
        assertThat(needsBucket("10000", "4500").status()).isEqualTo("WARNING"); // 90%
    }

    @Test
    void summary_bucketStatus_atExactly100_isOverBudget() {
        assertThat(needsBucket("10000", "5000").status()).isEqualTo("OVER_BUDGET");
    }

    @Test
    void summary_bucketStatus_overTheTarget_isOverBudget() {
        assertThat(needsBucket("10000", "6000").status()).isEqualTo("OVER_BUDGET"); // 120%
    }

    @Test
    void summary_bucketStatus_withZeroTarget_isOnTrack_evenWithSpend() {
        BudgetRuleSummaryResponse.BucketSummary needs = needsBucket("0", "500");
        assertThat(needs.percentUsed()).isZero();
        assertThat(needs.status()).isEqualTo("ON_TRACK");
    }

    @Test
    void summary_savingsBucket_getsTheSameStatusAsTheOthers() {
        BudgetRule rule = BudgetRule.builder()
                .user(user).ruleType(BudgetRuleType.FIFTY_THIRTY_TWENTY)
                .monthlyIncome(new BigDecimal("10000")).needsPct(50).wantsPct(30).savingsPct(20)
                .build();
        when(budgetRuleRepository.findByUser(user)).thenReturn(Optional.of(rule));
        when(categoryRepository.findAllByUser(user)).thenReturn(List.of(
                Category.builder().id(4L).name("Emergency fund").bucket(Bucket.SAVINGS).user(user).build()));
        List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(new Object[]{4L, new BigDecimal("2500")}); // 125% of the 2000 savings target
        when(expenseRepository.sumByCategoryAndMonth(user, 2026, 6)).thenReturn(rows);

        var savings = service.summary(user, "2026-06").buckets().stream()
                .filter(b -> b.bucket() == Bucket.SAVINGS).findFirst().orElseThrow();
        assertThat(savings.status()).isEqualTo("OVER_BUDGET");
    }
}
