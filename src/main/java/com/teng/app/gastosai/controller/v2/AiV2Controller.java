package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.config.RequiresFeature;
import com.teng.app.gastosai.controller.AiController;
import com.teng.app.gastosai.dto.AiQueryRequest;
import com.teng.app.gastosai.dto.AiQueryResponse;
import com.teng.app.gastosai.dto.ChatRequest;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.dto.v2.ChatResponseV2;
import com.teng.app.gastosai.dto.v2.ParsedExpenseResultV2;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** {@link AiController} with money as integer centavos. See the package javadoc. */
@RestController
@RequestMapping("/api/v2/ai")
@RequiredArgsConstructor
public class AiV2Controller {

	private final AiController delegate;

	/**
	 * Unchanged shape: the answer is prose the assistant wrote. Any amount in it is already
	 * formatted for a reader, so there is no numeric field whose type this version changes.
	 */
	@PostMapping("/query")
	@RequiresFeature(FeatureKey.AI_ANALYTICS)
	@Operation(operationId = "v2AiQuery")
	public AiQueryResponse query(@Valid @RequestBody AiQueryRequest request,
			@AuthenticationPrincipal User user) {
		return delegate.query(request, user);
	}

	@PostMapping(value = "/vision", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(operationId = "v2AiVision")
	public ParsedExpenseResultV2 vision(
			@RequestParam("file") MultipartFile file,
			@RequestParam(value = "question", required = false) String question,
			@RequestParam(value = "mode", required = false, defaultValue = "plain") String mode,
			@AuthenticationPrincipal User user) {
		return ParsedExpenseResultV2.from(delegate.vision(file, question, mode, user));
	}

	/**
	 * The v1 turn with the money inside {@code result} restated as integer centavos (TEN-308).
	 *
	 * <p>This used to return the v1 body verbatim, on the reasoning {@link #query} gives — but that
	 * reasoning only holds for prose. A chat turn also carries a structured payload, and three of
	 * those payloads are money-bearing, so v2 was serving decimals on the one path nobody could see:
	 * {@code ChatResponseV2} did not exist as a schema either.
	 *
	 * <p>The status is carried over from the delegate rather than assumed: the v1 handler wraps the
	 * circuit breaker, and a fallback turn is still a turn.
	 */
	@PostMapping("/chat")
	@RequiresFeature(FeatureKey.NL_CHATBOT)
	@Operation(operationId = "v2AiChat",
			summary = "Send a message to the assistant",
			description = "One assistant turn. Money inside `result` is an integer number of "
					+ "centavos; a `preview` turn's `params` are the opaque arguments to echo back to "
					+ "POST /ai/chat/confirm, and are not converted.")
	public ResponseEntity<ChatResponseV2> chat(@Valid @RequestBody ChatRequest req,
			@AuthenticationPrincipal User user) {
		ResponseEntity<ChatResponse> v1 = delegate.chat(req, user);
		return ResponseEntity.status(v1.getStatusCode()).body(ChatResponseV2.from(v1.getBody()));
	}
}
