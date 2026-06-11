package com.teng.app.gastosai.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ParsedExpenseResult(
        BigDecimal amount,
        String category,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime date,
        String description,
        String confidence,
        boolean saveable,
        String hint,
        String rejectionMessage
) {}