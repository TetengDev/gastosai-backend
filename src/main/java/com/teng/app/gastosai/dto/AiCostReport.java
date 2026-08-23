package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * Cost to serve, computed from recorded {@code ai_usage} rows for a period.
 *
 * <p>Costs here are recomputed from the recorded token counts at the rates in {@link #pricing},
 * not read back from the {@code estimated_cost_usd} column. Those stored values were priced at
 * whatever rates were configured the moment the call was made, so summing them mixes prices — and
 * a report whose whole purpose is to state the price it used cannot be built on that.
 */
@Schema(description = "Cost to serve per user and per plan, recomputed from recorded AI usage.")
public record AiCostReport(

        @Schema(description = "First day counted, inclusive, in Asia/Manila.", example = "2026-08-01")
        LocalDate periodStart,

        @Schema(description = "Last day counted, inclusive, in Asia/Manila.", example = "2026-08-24")
        LocalDate periodEnd,

        @Schema(description = "The per-token prices used, and when they were last checked.")
        AiCostPricing pricing,

        @Schema(description = "Every plan with recorded usage in the period.")
        List<AiCostByPlanItem> byPlan,

        @Schema(description = "Every user with recorded usage in the period, most expensive first.")
        List<AiCostByUserItem> byUser
) {}
