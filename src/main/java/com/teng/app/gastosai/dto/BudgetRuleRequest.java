package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.BudgetRuleType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Upsert a user's budgeting rule. For presets the percentages are derived from the type; for
 * {@link BudgetRuleType#CUSTOM} the three percentages are required and must sum to 100.
 */
public record BudgetRuleRequest(
        @NotNull BudgetRuleType ruleType,
        @NotNull @DecimalMin("0.0") BigDecimal monthlyIncome,
        Integer needsPct,
        Integer wantsPct,
        Integer savingsPct) {
}
