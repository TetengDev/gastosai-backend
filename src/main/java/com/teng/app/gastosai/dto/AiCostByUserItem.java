package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/** What one user cost to serve over the reporting period, split text from vision. */
@Schema(description = "Cost to serve one user over the reporting period.")
public record AiCostByUserItem(

        @Schema(description = "The user's id.", example = "42")
        Long userId,

        @Schema(description = "The plan the user is on now — `FREE`, `PREMIUM` or `TRIAL`. This is "
                + "the plan at report time, not necessarily the plan held during the period.",
                example = "PREMIUM")
        String plan,

        @Schema(description = "Text-only calls: chat, insights, summaries, category suggestions.")
        AiCostBreakdown text,

        @Schema(description = "Vision calls: receipt analysis.")
        AiCostBreakdown vision,

        @Schema(description = "Text plus vision, in USD.", example = "0.183921")
        BigDecimal totalCostUsd
) {}
