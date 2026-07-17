package com.teng.app.gastosai.dto;

import java.util.List;

public record ObservabilitySummary(
        long totalUsers,
        long signupsToday,
        long signups7d,
        long signups30d,
        long activeUsers24h,
        long activeUsers7d,
        List<TopUserItem> topUsers30d
) {
    public record TopUserItem(Long userId, long requests) {}
}
