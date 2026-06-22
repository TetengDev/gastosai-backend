package com.teng.app.gastosai.entity;

/**
 * Gateable product capabilities. These are the single source of truth for feature names used by the
 * entitlement system (DB rows in {@code plan_features}, the {@code @RequiresFeature} annotation, and
 * the frontend). Add a value here, then grant it to a plan via the seeder/migration.
 */
public enum FeatureKey {
    AI_ANALYTICS,
    NL_CHATBOT,
    EXPORT_CSV,
    EXPORT_PDF,
    BUDGET_FORECASTING,
    TREND_ANALYSIS,
    CUSTOM_CATEGORIES,
    UNLIMITED_TRANSACTIONS,
    ADVANCED_INSIGHTS,
    ANOMALY_DETECTION,
    CHAT_PERSONAS
}
