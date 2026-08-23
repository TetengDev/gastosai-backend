package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Requests, tokens and recomputed USD cost for one slice of recorded usage — either the text
 * slice or the vision slice of a user or a plan.
 */
@Schema(description = "Requests, tokens and USD cost for one slice (text or vision) of recorded AI usage.")
public record AiCostBreakdown(

        @Schema(description = "Metered requests in the slice, including failed ones.", example = "142")
        long requests,

        @Schema(description = "Prompt tokens summed over the slice.", example = "184320")
        long inputTokens,

        @Schema(description = "Completion tokens summed over the slice.", example = "31044")
        long outputTokens,

        @Schema(description = "Cost in USD, recomputed from the tokens above at this report's "
                + "per-token prices. Six decimal places: a single call costs far less than a cent.",
                example = "0.046292")
        BigDecimal costUsd
) {}
