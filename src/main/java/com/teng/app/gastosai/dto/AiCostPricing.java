package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The per-token prices the report multiplied recorded tokens by, and when a human last checked
 * them against the provider's published rates.
 *
 * <p>The report carries these rather than assuming the reader knows them, because the numbers it
 * produces are only as good as the prices behind them and provider pricing changes without notice.
 * A margin decision taken against a stale rate is worse than no number at all, so the staleness is
 * on the face of the report.
 */
@Schema(description = "The per-token prices used to compute this report, and when they were last checked.")
public record AiCostPricing(

        @Schema(description = "USD per million prompt tokens for text calls.", example = "0.15")
        BigDecimal textInputPerMtokUsd,

        @Schema(description = "USD per million completion tokens for text calls.", example = "0.60")
        BigDecimal textOutputPerMtokUsd,

        @Schema(description = "USD per million prompt tokens for vision (receipt) calls. An order "
                + "of magnitude above the text rate — an image is billed as a large token block.",
                example = "2.50")
        BigDecimal visionInputPerMtokUsd,

        @Schema(description = "USD per million completion tokens for vision (receipt) calls.", example = "10.00")
        BigDecimal visionOutputPerMtokUsd,

        @Schema(description = "The date a human last reconciled these rates against the provider's "
                + "published pricing. Treat the report as an estimate that ages from this date.",
                example = "2026-08-24")
        LocalDate pricesLastCheckedOn,

        @Schema(description = "Where the rates were checked.", example = "OpenAI API pricing page")
        String pricesSource
) {}
