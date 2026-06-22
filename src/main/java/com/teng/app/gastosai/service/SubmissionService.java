package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.SubmissionRequest;
import com.teng.app.gastosai.dto.SubmissionResponse;
import com.teng.app.gastosai.entity.Submission;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.SubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubmissionService {

	private final SubmissionRepository submissionRepository;
	private final EmailSender emailSender;

	@Value("${gastos.contact.notification-email:}")
	private String notificationEmail;

	@Transactional
	public SubmissionResponse create(SubmissionRequest request) {
		Submission saved = submissionRepository.save(Submission.builder()
				.type(request.type())
				.name(blankToNull(request.name()))
				.email(blankToNull(request.email()))
				.message(request.message().trim())
				.build());
		notifyContact(saved);
		return SubmissionResponse.from(saved);
	}

	/** Best-effort: email the contact inbox so messages aren't only visible in the admin page. */
	private void notifyContact(Submission s) {
		if (notificationEmail == null || notificationEmail.isBlank()) {
			return;
		}
		try {
			String body = "New %s submission%s%s:\n\n%s".formatted(
					s.getType(),
					s.getName() != null ? " from " + s.getName() : "",
					s.getEmail() != null ? " <" + s.getEmail() + ">" : "",
					s.getMessage());
			emailSender.sendNotification(notificationEmail, "GastosAI " + s.getType() + " submission", body);
		} catch (Exception ex) {
			log.warn("contact_notification_failed type={} err={}", s.getType(), ex.getMessage());
		}
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
