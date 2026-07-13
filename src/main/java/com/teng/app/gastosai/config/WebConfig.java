package com.teng.app.gastosai.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

	@Value("${cors.allowed-origins:http://localhost:5173}")
	private String[] allowedOrigins;

	private final FeatureAccessInterceptor featureAccessInterceptor;
	private final AiRateLimitInterceptor aiRateLimitInterceptor;
	private final AiKeyContextInterceptor aiKeyContextInterceptor;
	private final ViewAsInterceptor viewAsInterceptor;
	private final PublicRateLimitInterceptor publicRateLimitInterceptor;
	private final AuthenticatedWriteRateLimitInterceptor authenticatedWriteRateLimitInterceptor;

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		// Normalize configured origins: trim whitespace and strip trailing slashes. The browser's
		// Origin header never has a trailing slash, so a misconfigured "https://app.example/" would
		// otherwise silently block every cross-origin call.
		String[] origins = java.util.Arrays.stream(allowedOrigins)
				.filter(o -> o != null && !o.isBlank())
				.map(o -> o.trim().replaceAll("/+$", ""))
				.toArray(String[]::new);
		registry.addMapping("/**")
				.allowedOriginPatterns(origins)
				.allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
				.allowedHeaders("Authorization", "Content-Type", "Accept",
						"X-View-As-Plan", "X-View-As-Ai", "X-Request-Id")
				.maxAge(3600);
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(viewAsInterceptor);
		registry.addInterceptor(publicRateLimitInterceptor)
				.addPathPatterns("/auth/login", "/auth/register", "/auth/magic-link",
						"/auth/magic-link/verify", "/submissions");
		// /expenses/parse also calls the LLM, so it needs the per-user key (BYO) like /ai/**.
		// /ai/usage is informational only (no LLM call), so it is exempt from the key and rate-limit gates.
		registry.addInterceptor(aiKeyContextInterceptor).addPathPatterns("/ai/**", "/expenses/parse").excludePathPatterns("/ai/usage");
		registry.addInterceptor(aiRateLimitInterceptor).addPathPatterns("/ai/**", "/expenses/parse").excludePathPatterns("/ai/usage");
		registry.addInterceptor(authenticatedWriteRateLimitInterceptor)
				.addPathPatterns("/expenses/**", "/categories/**", "/budgets/**", "/recurring/**",
						"/goals/**", "/alerts/**")
				.excludePathPatterns("/ai/**", "/auth/**", "/submissions", "/webhooks/**");
		registry.addInterceptor(featureAccessInterceptor);
	}
}
