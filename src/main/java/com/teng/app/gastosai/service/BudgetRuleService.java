package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.BucketAssignmentRequest;
import com.teng.app.gastosai.dto.BudgetRuleRequest;
import com.teng.app.gastosai.dto.BudgetRuleResponse;
import com.teng.app.gastosai.dto.BudgetRuleSummaryResponse;
import com.teng.app.gastosai.dto.BudgetRuleSummaryResponse.BucketSummary;
import com.teng.app.gastosai.entity.Bucket;
import com.teng.app.gastosai.entity.BudgetRule;
import com.teng.app.gastosai.entity.BudgetRuleType;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.BudgetRuleRepository;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BudgetRuleService {

    private final BudgetRuleRepository budgetRuleRepository;
    private final CategoryRepository categoryRepository;
    private final ExpenseRepository expenseRepository;

    @Transactional(readOnly = true)
    public BudgetRuleResponse get(User user) {
        return budgetRuleRepository.findByUser(user)
                .map(this::toResponse)
                .orElseGet(() -> new BudgetRuleResponse(
                        false, BudgetRuleType.FIFTY_THIRTY_TWENTY, BigDecimal.ZERO,
                        BudgetRuleType.FIFTY_THIRTY_TWENTY.needs,
                        BudgetRuleType.FIFTY_THIRTY_TWENTY.wants,
                        BudgetRuleType.FIFTY_THIRTY_TWENTY.savings));
    }

    @Transactional
    public BudgetRuleResponse setEnabled(User user, boolean enabled) {
        BudgetRule rule = budgetRuleRepository.findByUser(user).orElseGet(() -> BudgetRule.builder()
                .user(user)
                .ruleType(BudgetRuleType.FIFTY_THIRTY_TWENTY)
                .monthlyIncome(BigDecimal.ZERO)
                .needsPct(BudgetRuleType.FIFTY_THIRTY_TWENTY.needs)
                .wantsPct(BudgetRuleType.FIFTY_THIRTY_TWENTY.wants)
                .savingsPct(BudgetRuleType.FIFTY_THIRTY_TWENTY.savings)
                .build());
        rule.setEnabled(enabled);
        rule.setUpdatedAt(java.time.LocalDateTime.now());
        return toResponse(budgetRuleRepository.save(rule));
    }

    @Transactional
    public BudgetRuleResponse upsert(User user, BudgetRuleRequest request) {
        int needs;
        int wants;
        int savings;
        if (request.ruleType() == BudgetRuleType.CUSTOM) {
            if (request.needsPct() == null || request.wantsPct() == null || request.savingsPct() == null) {
                throw new IllegalArgumentException("Custom rule requires needs/wants/savings percentages");
            }
            needs = request.needsPct();
            wants = request.wantsPct();
            savings = request.savingsPct();
            if (needs < 0 || wants < 0 || savings < 0 || needs + wants + savings != 100) {
                throw new IllegalArgumentException("Percentages must be non-negative and sum to 100");
            }
        } else {
            needs = request.ruleType().needs;
            wants = request.ruleType().wants;
            savings = request.ruleType().savings;
        }

        BudgetRule rule = budgetRuleRepository.findByUser(user).orElseGet(() -> BudgetRule.builder()
                .user(user)
                .build());
        rule.setRuleType(request.ruleType());
        rule.setMonthlyIncome(request.monthlyIncome().setScale(4, RoundingMode.HALF_UP));
        rule.setNeedsPct(needs);
        rule.setWantsPct(wants);
        rule.setSavingsPct(savings);
        rule.setEnabled(true); // saving a rule is an explicit use of the feature
        rule.setUpdatedAt(java.time.LocalDateTime.now());
        return toResponse(budgetRuleRepository.save(rule));
    }

    @Transactional
    public void assignBuckets(User user, BucketAssignmentRequest request) {
        for (BucketAssignmentRequest.Item item : request.assignments()) {
            categoryRepository.findByIdAndUser(item.categoryId(), user).ifPresent(category -> {
                category.setBucket(item.bucket());
                categoryRepository.save(category);
            });
        }
    }

    @Transactional(readOnly = true)
    public BudgetRuleSummaryResponse summary(User user, String month) {
        YearMonth ym;
        try {
            ym = YearMonth.parse(month);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid month, expected format YYYY-MM: " + month);
        }
        BudgetRuleResponse rule = get(user);

        Map<Long, Bucket> bucketByCategory = new HashMap<>();
        for (Category c : categoryRepository.findAllByUser(user)) {
            bucketByCategory.put(c.getId(), c.getBucket());
        }

        Map<Bucket, BigDecimal> spentByBucket = new EnumMap<>(Bucket.class);
        for (Bucket b : Bucket.values()) {
            spentByBucket.put(b, BigDecimal.ZERO);
        }
        BigDecimal unassigned = BigDecimal.ZERO;

        for (Object[] row : expenseRepository.sumByCategoryAndMonth(user, ym.getYear(), ym.getMonthValue())) {
            Long categoryId = (Long) row[0];
            BigDecimal sum = (BigDecimal) row[1];
            if (sum == null) {
                continue;
            }
            Bucket bucket = bucketByCategory.get(categoryId);
            if (bucket == null) {
                unassigned = unassigned.add(sum);
            } else {
                spentByBucket.merge(bucket, sum, BigDecimal::add);
            }
        }

        BigDecimal income = rule.monthlyIncome();
        List<BucketSummary> buckets = List.of(
                bucketSummary(Bucket.NEEDS, rule.needsPct(), income, spentByBucket.get(Bucket.NEEDS)),
                bucketSummary(Bucket.WANTS, rule.wantsPct(), income, spentByBucket.get(Bucket.WANTS)),
                bucketSummary(Bucket.SAVINGS, rule.savingsPct(), income, spentByBucket.get(Bucket.SAVINGS)));

        return new BudgetRuleSummaryResponse(month, rule.ruleType(), money(income), buckets, money(unassigned));
    }

    private BucketSummary bucketSummary(Bucket bucket, int percent, BigDecimal income, BigDecimal spent) {
        BigDecimal target = income.multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal spentMoney = money(spent);
        double percentUsed = target.signum() > 0
                ? spentMoney.divide(target, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue()
                : 0.0;
        return new BucketSummary(bucket, percent, target, spentMoney, target.subtract(spentMoney), percentUsed);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private BudgetRuleResponse toResponse(BudgetRule rule) {
        return new BudgetRuleResponse(rule.isEnabled(), rule.getRuleType(),
                rule.getMonthlyIncome().setScale(2, RoundingMode.HALF_UP),
                rule.getNeedsPct(), rule.getWantsPct(), rule.getSavingsPct());
    }
}
