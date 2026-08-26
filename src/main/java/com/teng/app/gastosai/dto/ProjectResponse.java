package com.teng.app.gastosai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** A billing tag — the project or client an expense can be attributed to. */
public record ProjectResponse(
		@Schema(description = "Stable across renames. Filter and report by this, not by the name.")
		Long id,
		String name
) {}
