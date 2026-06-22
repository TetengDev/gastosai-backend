package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.Bucket;

public record CategoryResponse(
		Long id,
		String name,
		String icon,
		Bucket bucket) {
}
