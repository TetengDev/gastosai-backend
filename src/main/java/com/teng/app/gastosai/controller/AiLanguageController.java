package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.ai.AiLanguageRegistry;
import com.teng.app.gastosai.dto.AiLanguageOption;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The languages a client may offer for AI prose.
 *
 * <p>Served rather than hardcoded in each client so that adding a language is a configuration
 * change on the server and not a release of two apps. This is the <em>AI</em> list: it is not the
 * set of languages the interface can be rendered in, which is bounded by the translated catalogues.
 *
 * <p>No model call happens here, so the route carries neither the key context nor the AI rate
 * limit — it reads configuration and returns it.
 */
@RestController
@RequestMapping("/ai/languages")
@RequiredArgsConstructor
public class AiLanguageController {

	private final AiLanguageRegistry registry;

	@GetMapping
	public List<AiLanguageOption> list() {
		return registry.supported().stream()
				.map(language -> new AiLanguageOption(language.code(), language.displayName()))
				.toList();
	}
}
