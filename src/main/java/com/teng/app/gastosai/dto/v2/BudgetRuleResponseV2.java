package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.BudgetRuleResponse;
import com.teng.app.gastosai.entity.BudgetRuleType;

/** {@link BudgetRuleResponse} with {@code monthlyIncome} as integer centavos. */
public record BudgetRuleResponseV2(
		boolean enabled,
		BudgetRuleType ruleType,
		Long monthlyIncome,
		int needsPct,
		int wantsPct,
		int savingsPct
) {

	public static BudgetRuleResponseV2 from(BudgetRuleResponse v1) {
		return new BudgetRuleResponseV2(
				v1.enabled(),
				v1.ruleType(),
				Money.toCentavos(v1.monthlyIncome()),
				v1.needsPct(),
				v1.wantsPct(),
				v1.savingsPct());
	}
}
