package com.teng.app.gastosai.ai;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Chatbot action vocabulary. The {@code key} values are the wire contract with the
 * LLM function-calling output ({@link ChatToolCall#toolName()}); they must match the
 * tool names declared in the SqlGenerator prompt schemas exactly.
 */
public enum ChatTool {
    CREATE_EXPENSE("create_expense"),
    UPDATE_EXPENSE("update_expense"),
    DELETE_EXPENSE("delete_expense"),
    CREATE_BUDGET("create_budget"),
    UPDATE_BUDGET("update_budget"),
    DELETE_BUDGET("delete_budget"),
    CREATE_GOAL("create_goal"),
    UPDATE_GOAL("update_goal"),
    DELETE_GOAL("delete_goal"),
    CREATE_RECURRING("create_recurring"),
    UPDATE_RECURRING("update_recurring"),
    DELETE_RECURRING("delete_recurring"),
    CREATE_CATEGORY("create_category"),
    RENAME_CATEGORY("rename_category"),
    DELETE_CATEGORY("delete_category"),
    LIST_CATEGORIES("list_categories"),
    UPDATE_PROFILE("update_profile"),
    GET_SUBSCRIPTION("get_subscription"),
    LIST_GOALS("list_goals"),
    LIST_BUDGETS("list_budgets"),
    LIST_RECURRING("list_recurring"),
    LIST_ALERTS("list_alerts"),
    SEARCH_EXPENSES("search_expenses"),
    GET_CATEGORY_TOTALS("get_category_totals"),
    GET_MONTHLY_REPORT("get_monthly_report"),
    MARK_ALERT_READ("mark_alert_read"),
    DISMISS_ALERT("dismiss_alert"),
    DELETE_ALERT("delete_alert"),
    SET_DEFAULT_CATEGORY("set_default_category"),
    SET_CATEGORY_ICON("set_category_icon"),
    DELETE_EXPENSES("delete_expenses"),
    RECATEGORIZE_EXPENSES("recategorize_expenses"),
    /** No actionable tool — the assistant replied with plain text. */
    TEXT("text");

    private static final Map<String, ChatTool> BY_KEY = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ChatTool::key, Function.identity()));

    private final String key;

    ChatTool(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public boolean isCreate() {
        return name().startsWith("CREATE_");
    }

    public boolean isDestructive() {
        return this == DELETE_EXPENSES || this == RECATEGORIZE_EXPENSES;
    }

    /** Resolves an LLM-returned tool name, falling back to {@link #TEXT} for anything unknown. */
    public static ChatTool fromKey(String key) {
        return BY_KEY.getOrDefault(key, TEXT);
    }
}
