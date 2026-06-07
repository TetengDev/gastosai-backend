package com.teng.app.gastosai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record CategoryResponse(
		@JsonIgnore
		Long id,
		String name) {
}

