package com.teng.app.gastosai.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ParsedExpenseResult(
        BigDecimal amount,
        String category,
        // Returned to clients by ExpenseController and AiController, so it follows the same
        // rule as every other API timestamp: explicit +08:00, applied by JacksonTimeConfig.
        // Inbound parsing stays lenient — the AI parser feeds this offset-less JSON from a
        // language model, and that must keep working.
        LocalDateTime date,
        String description,
        String confidence,
        boolean saveable,
        String hint,
        String rejectionMessage
) {}