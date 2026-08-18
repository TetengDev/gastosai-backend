package com.teng.app.gastosai.dto.v2;

import com.teng.app.gastosai.dto.BudgetRuleRequest;
import com.teng.app.gastosai.entity.BudgetRuleType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** {@link BudgetRuleRequest} with {@code monthlyIncome} as integer centavos. */
public record BudgetRuleRequestV2(
		@NotNull BudgetRuleType ruleType,
		@NotNull @Min(0) Long monthlyIncome,
		Integer needsPct,
		Integer wantsPct,
		Integer savingsPct
) {

	public BudgetRuleRequest toV1() {
		return new BudgetRuleRequest(ruleType, Money.toDecimal(monthlyIncome), needsPct, wantsPct, savingsPct);
	}
}
