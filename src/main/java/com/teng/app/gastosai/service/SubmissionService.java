package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.SubmissionRequest;
import com.teng.app.gastosai.dto.SubmissionResponse;
import com.teng.app.gastosai.entity.Submission;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.SubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SubmissionService {

	private final SubmissionRepository submissionRepository;

	@Transactional
	public SubmissionResponse create(SubmissionRequest request) {
		Submission saved = submissionRepository.save(Submission.builder()
				.type(request.type())
				.name(blankToNull(request.name()))
				.email(blankToNull(request.email()))
				.message(request.message().trim())
				.build());
		return SubmissionResponse.from(saved);
	}

	@Transactional(readOnly = true)
	public List<SubmissionResponse> list() {
		return submissionRepository.findAllByOrderByCreatedAtDesc().stream()
				.map(SubmissionResponse::from)
				.toList();
	}

	@Transactional
	public SubmissionResponse markHandled(Long id) {
		Submission s = submissionRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Submission not found: " + id));
		s.setHandled(true);
		return SubmissionResponse.from(submissionRepository.save(s));
	}

	private static String blankToNull(String s) {
		return (s == null || s.isBlank()) ? null : s.trim();
	}
}
