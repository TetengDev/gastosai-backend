package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.controller.SubmissionController;
import com.teng.app.gastosai.dto.SubmissionRequest;
import com.teng.app.gastosai.dto.SubmissionResponse;
import io.swagger.v3.oas.annotations.Operation;
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

/**
 * {@link SubmissionController} on the v2 path; a contact message carries no money.
 *
 * <p>The anonymous/ADMIN split on this prefix is the same one v1 has, and is enforced by the same
 * rules: {@code SecurityConfig} applies both {@code PublicEndpoints.RULES} and its own
 * {@code ADMIN_RULES} at every version prefix, so {@code POST} stays public here while the rest of
 * the prefix stays ADMIN.
 */
@RestController
@RequestMapping("/api/v2/submissions")
@RequiredArgsConstructor
public class SubmissionV2Controller {

	private final SubmissionController delegate;

	/** Public — anyone can send a contact/feedback message. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(operationId = "v2CreateSubmission")
	public SubmissionResponse create(@Valid @RequestBody SubmissionRequest request) {
		return delegate.create(request);
	}

	/** Admin only (enforced in SecurityConfig). */
	@GetMapping
	@Operation(operationId = "v2ListSubmissions")
	public List<SubmissionResponse> list() {
		return delegate.list();
	}

	/** Admin only — mark a message as handled. */
	@PatchMapping("/{id}/handled")
	@Operation(operationId = "v2MarkSubmissionHandled")
	public SubmissionResponse markHandled(@PathVariable Long id) {
		return delegate.markHandled(id);
	}
}
