package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.dto.AiQueryRequest;
import com.teng.app.gastosai.dto.AiQueryResponse;
import com.teng.app.gastosai.service.AiQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {

	private final AiQueryService aiQueryService;

	@PostMapping("/query")
	public AiQueryResponse query(@Valid @RequestBody AiQueryRequest request) {
		return aiQueryService.runNaturalLanguageQuery(request.question(), request.mode());
	}
}
