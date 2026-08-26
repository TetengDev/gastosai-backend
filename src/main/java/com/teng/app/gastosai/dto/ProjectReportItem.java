package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * What one engagement has cost, in the user's base currency.
 *
 * <p>Untagged expenses are not a row here: this report answers "what does each engagement cost
 * me", and everything not attributed to one is the ordinary spending the category reports already
 * cover.
 */
public record ProjectReportItem(
		@Schema(description = "Stable across renames.") Long projectId,
		String project,
		BigDecimal total
) {}
