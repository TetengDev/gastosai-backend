package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.config.FeatureProperties;
import com.teng.app.gastosai.controller.FeatureController;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@link FeatureController} on the v2 path; feature flags carry no money. */
@RestController
@RequestMapping("/api/v2/features")
@RequiredArgsConstructor
public class FeatureV2Controller {

	private final FeatureController delegate;

	@GetMapping
	@Operation(operationId = "v2Features")
	public FeatureProperties features() {
		return delegate.features();
	}
}
