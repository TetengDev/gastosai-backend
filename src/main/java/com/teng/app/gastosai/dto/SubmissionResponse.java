package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.Submission;
import com.teng.app.gastosai.entity.SubmissionType;

import java.time.LocalDateTime;

public record SubmissionResponse(
		Long id,
		SubmissionType type,
		String name,
		String email,
		String message,
		LocalDateTime createdAt,
		boolean handled
) {
	public static SubmissionResponse from(Submission s) {
		return new SubmissionResponse(
				s.getId(), s.getType(), s.getName(), s.getEmail(),
				s.getMessage(), s.getCreatedAt(), s.isHandled());
	}
}
