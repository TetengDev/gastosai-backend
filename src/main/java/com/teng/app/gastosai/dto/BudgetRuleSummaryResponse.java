package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.Bucket;
import com.teng.app.gastosai.entity.BudgetRuleType;

import java.math.BigDecimal;
import java.util.List;

/** Target (income × bucket %) vs actual spend per bucket for a given month. */
public record BudgetRuleSummaryResponse(
        String month,
        BudgetRuleType ruleType,
        BigDecimal monthlyIncome,
        List<BucketSummary> buckets,
        BigDecimal unassignedSpent) {

    public record BucketSummary(
            Bucket bucket,
            int percent,
            BigDecimal target,
            BigDecimal spent,
            BigDecimal remaining,
            double percentUsed) {
    }
}
