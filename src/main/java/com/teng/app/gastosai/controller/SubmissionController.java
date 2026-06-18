package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.SubmissionRequest;
import com.teng.app.gastosai.dto.SubmissionResponse;
import com.teng.app.gastosai.service.SubmissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/submissions")
@RequiredArgsConstructor
public class SubmissionController {

	private final SubmissionService submissionService;

	/** Public — anyone can send a contact/feedback message. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public SubmissionResponse create(@Valid @RequestBody SubmissionRequest request) {
		return submissionService.create(request);
	}

	/** Admin only (enforced in SecurityConfig). */
	@GetMapping
	public List<SubmissionResponse> list() {
		return submissionService.list();
	}

	/** Admin only — mark a message as handled. */
	@PatchMapping("/{id}/handled")
	public SubmissionResponse markHandled(@PathVariable Long id) {
		return submissionService.markHandled(id);
	}
}
