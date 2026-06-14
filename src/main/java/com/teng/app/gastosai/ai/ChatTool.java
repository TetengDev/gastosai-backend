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
    DELETE_GOAL("delete_goal"),
    CREATE_RECURRING("create_recurring"),
    DELETE_RECURRING("delete_recurring"),
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

    /** Resolves an LLM-returned tool name, falling back to {@link #TEXT} for anything unknown. */
    public static ChatTool fromKey(String key) {
        return BY_KEY.getOrDefault(key, TEXT);
    }
}
