package com.teng.app.gastosai.ai;

public enum AiFeature {
    EXPENSE_INSIGHT,
    MONTHLY_SUMMARY,
    BUDGET_ADVICE,
    RECEIPT_ANALYSIS,
    CATEGORY_SUGGESTION,
    CHAT_CRUD_ASSISTANT,
    CHAT_SUMMARY,
    CHAT_CONTEXT_RESOLUTION;

    public boolean isVision() {
        return this == RECEIPT_ANALYSIS;
    }
}
