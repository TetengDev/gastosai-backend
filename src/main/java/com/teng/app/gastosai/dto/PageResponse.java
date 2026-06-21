package com.teng.app.gastosai.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable, explicit pagination envelope returned by paged endpoints. We do not serialize Spring's
 * {@code Page} directly because its JSON shape is not part of a stable API contract across versions.
 */
public record PageResponse<T>(
		List<T> content,
		int page,
		int size,
		long totalElements,
		int totalPages,
		boolean last) {

	public static <T> PageResponse<T> of(Page<T> page) {
		return new PageResponse<>(
				page.getContent(),
				page.getNumber(),
				page.getSize(),
				page.getTotalElements(),
				page.getTotalPages(),
				page.isLast());
	}
}
