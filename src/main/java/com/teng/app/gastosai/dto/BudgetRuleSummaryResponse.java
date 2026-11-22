package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.Bucket;
import com.teng.app.gastosai.entity.BudgetRuleType;
import io.swagger.v3.oas.annotations.media.Schema;

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
            double percentUsed,
            @Schema(description = "Banding derived from `percentUsed`, by the same thresholds as a category budget line: "
                    + "`WARNING` at 80%, `OVER_BUDGET` at 100%. It reports how much of the allocation is used; "
                    + "for the `SAVINGS` bucket the client decides the tone, since passing a savings target is "
                    + "not bad news.",
                    allowableValues = {"ON_TRACK", "WARNING", "OVER_BUDGET"})
            String status) {
    }
}
