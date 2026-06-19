package com.teng.app.gastosai.dto;

import java.time.LocalDateTime;

public record AiUsageResponse(
        String plan,
        long used,
        int limit,
        long remaining,
        long visionUsed,
        int visionLimit,
        boolean managed,
        LocalDateTime resetsAt
) {
}
