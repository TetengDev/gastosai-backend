package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.AiQueryRequest;
import com.teng.app.gastosai.dto.AiQueryResponse;
import com.teng.app.gastosai.dto.ParsedExpenseResult;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.AiQueryService;
import com.teng.app.gastosai.service.VisionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {

	private final AiQueryService aiQueryService;
	private final VisionService visionService;

	@PostMapping("/query")
	public AiQueryResponse query(@Valid @RequestBody AiQueryRequest request,
			@AuthenticationPrincipal User user) {
		return aiQueryService.runNaturalLanguageQuery(request.question(), request.mode(), user);
	}

	@PostMapping(value = "/vision", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ParsedExpenseResult vision(
			@RequestParam("file") MultipartFile file,
			@RequestParam(value = "question", required = false) String question,
			@RequestParam(value = "mode", required = false, defaultValue = "plain") String mode) {
		try {
			return visionService.analyze(question, file, mode);
		} catch (IOException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read file: " + e.getMessage());
		}
	}
}
