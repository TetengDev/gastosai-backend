package com.teng.app.gastosai.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class WebConfig implements WebMvcConfigurer {

	@Value("${cors.allowed-origins:http://localhost:5173}")
	private String[] allowedOrigins;

	/**
	 * The proxy layer whose {@code X-Forwarded-For} is believed, as addresses or CIDR blocks.
	 *
	 * <p>Empty by default, which means no header is believed and every IP-keyed control keys on the
	 * transport peer — see {@link ClientIps}. That default is deliberate: an unset value must fail
	 * closed, because the failure the other way is silent (the limiter still answers, it just
	 * answers per forged header value, which is how TEN-425 got past a limit of ten fourteen times).
	 * Set it to the edge's own addresses when one is in front of the app —
	 * {@code gastos.security.trusted-proxies=10.0.0.0/8} for a private-network ingress, or
	 * {@code 127.0.0.0/8,::1} for a proxy on the same host.
	 */
	@Value("${gastos.security.trusted-proxies:}")
	private String trustedProxies;

	private final FeatureAccessInterceptor featureAccessInterceptor;
	private final AiRateLimitInterceptor aiRateLimitInterceptor;
	private final AiKeyContextInterceptor aiKeyContextInterceptor;
	private final ViewAsInterceptor viewAsInterceptor;
	private final PublicRateLimitInterceptor publicRateLimitInterceptor;
	private final AuthenticatedWriteRateLimitInterceptor authenticatedWriteRateLimitInterceptor;

	/**
	 * Hands the configured edge to {@link ClientIps} before the first request is served.
	 *
	 * <p>{@code ClientIps.extract} is static — it is called from a controller and from an
	 * interceptor that both predate any notion of configuration — so the set is installed once here
	 * rather than injected. The log line is the only way an operator can tell which of the two modes
	 * is live, and getting that wrong is the whole finding, so it is logged either way.
	 */
	@PostConstruct
	void configureTrustedProxies() {
		ClientIps.configureTrustedProxies(trustedProxies);
		java.util.List<String> accepted = ClientIps.trustedProxies();
		if (accepted.isEmpty()) {
			log.info("No trusted proxies configured: IP-based limits key on the transport peer "
					+ "address and X-Forwarded-For is ignored");
		} else {
			log.info("Trusting X-Forwarded-For only from {}", accepted);
		}
	}

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
		// /ai/usage and /ai/languages are informational only (no LLM call), so they are exempt from the
		// key and rate-limit gates. /ai/languages in particular is the settings picker's source: gating
		// it on a BYO key would 402 the screen on which the user sets that key.
		// /expenses/quick-add parses free text through the model exactly as /expenses/parse does, so
		// it needs the same two gates. Registration here is by path, and a new route joins neither
		// list by default — the omission has no failing signal, because the missing line lives in a
		// file the endpoint's own change never touches. See observation #16.
		registry.addInterceptor(aiKeyContextInterceptor)
				.addPathPatterns(PublicEndpoints.atEveryVersion(
						"/ai/**", "/expenses/parse", "/expenses/quick-add"))
				.excludePathPatterns(PublicEndpoints.atEveryVersion("/ai/usage", "/ai/languages"));
		registry.addInterceptor(aiRateLimitInterceptor)
				.addPathPatterns(PublicEndpoints.atEveryVersion(
						"/ai/**", "/expenses/parse", "/expenses/quick-add"))
				.excludePathPatterns(PublicEndpoints.atEveryVersion("/ai/usage", "/ai/languages"));
		// /user/ai-settings/** covers PUT /user/ai-settings and DELETE /user/ai-settings/{provider}:
		// a "/**" pattern matches the base path too, which is why POST /expenses is limited by
		// "/expenses/**". GET stays usable — the interceptor exempts GET/HEAD/OPTIONS by method, so a
		// settings screen's read on load never spends a token from the write bucket. This is the one
		// authenticated write that had no bucket; it writes encrypted AI provider keys, and the next
		// shared-resource effect put behind it would otherwise be unthrottled (TEN-383).
		registry.addInterceptor(authenticatedWriteRateLimitInterceptor)
				.addPathPatterns(PublicEndpoints.atEveryVersion("/expenses/**", "/categories/**",
						"/budgets/**", "/recurring/**", "/goals/**", "/alerts/**",
						"/user/ai-settings/**"));
		registry.addInterceptor(featureAccessInterceptor);
	}
}
