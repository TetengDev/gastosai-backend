package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.config.FeatureProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/features")
@RequiredArgsConstructor
public class FeatureController {

	private final FeatureProperties featureProperties;

	@GetMapping
	public FeatureProperties features() {
		return featureProperties;
	}
}
