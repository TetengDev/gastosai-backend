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

	/**
	 * Registers the path-pattern interceptors across every API version.
	 *
	 * <p>Every pattern below goes through {@link PublicEndpoints#atEveryVersion}, which expands it
	 * over {@code VERSION_PREFIXES} — so {@code "/ai/**"} registers as both {@code /ai/**} and
	 * {@code /api/v2/ai/**}. Writing the v1 patterns alone would not narrow these gates, it would
	 * remove them: {@code /api/v2/ai/query} would then reach the LLM with no per-user key resolved
	 * and no quota metered, and the v2 write endpoints would be exempt from the write rate limit.
	 * A version prefix is not supposed to be a way around a rate limiter.
	 *
	 * <p>{@code viewAsInterceptor} and {@code featureAccessInterceptor} need no expansion — they are
	 * registered against every request, and {@code @RequiresFeature} is re-declared on the v2
	 * handlers so the plan gate resolves off the mapping that actually matched.
	 */
	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(viewAsInterceptor);
		// /webhooks/paymongo is public and unversioned (see PublicEndpoints), so it is registered
		// literally rather than through atEveryVersion. It is rate limited on its own budget and its
		// own bucket — see PublicRateLimitInterceptor#isWebhook — because the interactive limit would
		// shed genuine PayMongo bursts and their retries.
		registry.addInterceptor(publicRateLimitInterceptor)
				.addPathPatterns(PublicEndpoints.atEveryVersion("/auth/login", "/auth/register",
						"/auth/magic-link", "/auth/magic-link/verify", "/submissions"))
				.addPathPatterns("/webhooks/paymongo");
		// /expenses/parse also calls the LLM, so it needs the per-user key (BYO) like /ai/**.
		// /ai/usage is informational only (no LLM call), so it is exempt from the key and rate-limit gates.
		// /expenses/quick-add parses free text through the model exactly as /expenses/parse does, so
		// it needs the same two gates. Registration here is by path, and a new route joins neither
		// list by default — the omission has no failing signal, because the missing line lives in a
		// file the endpoint's own change never touches. See observation #16.
		registry.addInterceptor(aiKeyContextInterceptor)
				.addPathPatterns(PublicEndpoints.atEveryVersion(
						"/ai/**", "/expenses/parse", "/expenses/quick-add"))
				.excludePathPatterns(PublicEndpoints.atEveryVersion("/ai/usage"));
		registry.addInterceptor(aiRateLimitInterceptor)
				.addPathPatterns(PublicEndpoints.atEveryVersion(
						"/ai/**", "/expenses/parse", "/expenses/quick-add"))
				.excludePathPatterns(PublicEndpoints.atEveryVersion("/ai/usage"));
		registry.addInterceptor(authenticatedWriteRateLimitInterceptor)
				.addPathPatterns(PublicEndpoints.atEveryVersion("/expenses/**", "/categories/**",
						"/budgets/**", "/recurring/**", "/goals/**", "/alerts/**"));
		registry.addInterceptor(featureAccessInterceptor);
	}
}
