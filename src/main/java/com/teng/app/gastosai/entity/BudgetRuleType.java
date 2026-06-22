package com.teng.app.gastosai.entity;

/**
 * Rule-based budgeting techniques. Each preset carries its default needs/wants/savings split;
 * CUSTOM lets the user set their own percentages (validated to sum to 100).
 */
public enum BudgetRuleType {
    FIFTY_THIRTY_TWENTY(50, 30, 20),
    SEVENTY_TWENTY_TEN(70, 20, 10),
    CUSTOM(50, 30, 20);

    public final int needs;
    public final int wants;
    public final int savings;

    BudgetRuleType(int needs, int wants, int savings) {
        this.needs = needs;
        this.wants = wants;
        this.savings = savings;
    }
}
