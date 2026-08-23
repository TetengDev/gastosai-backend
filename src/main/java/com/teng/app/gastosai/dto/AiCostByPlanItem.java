package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * What one plan cost to serve over the reporting period. This is the row a price is set against:
 * {@code costPerActiveUserUsd} is the floor the plan's price has to clear.
 */
@Schema(description = "Cost to serve one plan over the reporting period.")
public record AiCostByPlanItem(

        @Schema(description = "`FREE`, `PREMIUM` or `TRIAL`.", example = "PREMIUM")
        String plan,

        @Schema(description = "Distinct users on this plan with recorded usage in the period. "
                + "Users who made no AI call are not counted — they cost nothing to serve.",
                example = "37")
        long activeUsers,

        @Schema(description = "Text-only calls: chat, insights, summaries, category suggestions.")
        AiCostBreakdown text,

        @Schema(description = "Vision calls: receipt analysis. Usually the smaller request count "
                + "and the larger bill.")
        AiCostBreakdown vision,

        @Schema(description = "Text plus vision, in USD.", example = "4.812900")
        BigDecimal totalCostUsd,

        @Schema(description = "`totalCostUsd` divided by `activeUsers`; zero when nobody was "
                + "active. Compare this against the plan's price to read the margin.",
                example = "0.130078")
        BigDecimal costPerActiveUserUsd
) {}
