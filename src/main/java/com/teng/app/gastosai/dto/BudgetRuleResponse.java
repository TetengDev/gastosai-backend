package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.BudgetRuleType;

import java.math.BigDecimal;

public record BudgetRuleResponse(
        BudgetRuleType ruleType,
        BigDecimal monthlyIncome,
        int needsPct,
        int wantsPct,
        int savingsPct) {
}
