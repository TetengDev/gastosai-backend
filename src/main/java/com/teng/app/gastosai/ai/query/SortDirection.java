package com.teng.app.gastosai.ai.query;

import java.util.Optional;

/** Allowlisted sort directions for grouped metrics. Rendered as a fixed SQL keyword, never interpolated user text. */
public enum SortDirection {
    ASC("ASC"),
    DESC("DESC");

    private final String sql;

    SortDirection(String sql) {
        this.sql = sql;
    }

    public String sql() {
        return sql;
    }

    public static Optional<SortDirection> fromString(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(SortDirection.valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
