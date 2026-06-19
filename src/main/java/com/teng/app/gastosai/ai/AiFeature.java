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

    /**
     * Whether usage of this feature consumes the user's monthly AI quota. Insights are
     * Caffeine-cached and effectively free to serve, so they are metered (for cost visibility)
     * but never counted against the cap — only chat turns and receipt uploads do.
     */
    public boolean countsTowardQuota() {
        return switch (this) {
            case EXPENSE_INSIGHT, MONTHLY_SUMMARY, BUDGET_ADVICE -> false;
            default -> true;
        };
    }
}
