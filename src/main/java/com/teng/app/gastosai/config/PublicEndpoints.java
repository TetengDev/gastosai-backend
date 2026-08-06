package com.teng.app.gastosai.config;

import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

import java.util.List;

/**
 * The endpoints that are reachable without a bearer token.
 *
 * <p>This list has two consumers that must agree: {@link SecurityConfig}, which permits them, and
 * {@link OpenApiConfig}, which is the only thing that decides whether an operation in the published
 * contract carries a {@code security} block. Keeping one list is the point — a second, hand-kept
 * copy in the spec customizer would drift the moment a matcher changed here, and the published
 * contract would go back to describing enforcement that does not match the running server.
 */
public final class PublicEndpoints {

	/**
	 * A permitted request. A {@code null} method means every method on the pattern is permitted.
	 */
	public record Rule(HttpMethod method, String pattern) {
	}

	public static final List<Rule> RULES = List.of(
			new Rule(HttpMethod.POST, "/auth/register"),
			new Rule(HttpMethod.POST, "/auth/login"),
			new Rule(HttpMethod.POST, "/auth/magic-link"),
			new Rule(HttpMethod.POST, "/auth/magic-link/verify"),
			new Rule(HttpMethod.POST, "/auth/google"),
			new Rule(HttpMethod.OPTIONS, "/**"),
			new Rule(null, "/actuator/info"),
			new Rule(null, "/actuator/health"),
			new Rule(null, "/features"),
			new Rule(null, "/error"),
			new Rule(null, "/swagger-ui/**"),
			new Rule(null, "/swagger-ui.html"),
			new Rule(null, "/v3/api-docs/**"),
			new Rule(HttpMethod.POST, "/submissions"),
			new Rule(HttpMethod.POST, "/webhooks/paymongo"),
			new Rule(HttpMethod.GET, "/subscription/pricing"));

	private static final AntPathMatcher MATCHER = new AntPathMatcher();

	private PublicEndpoints() {
	}

	/**
	 * Whether {@code method path} is permitted anonymously.
	 *
	 * <p>{@code path} may be an OpenAPI path template such as {@code /submissions/{id}}; a
	 * {@code {…}} placeholder is an ordinary path segment as far as the matcher is concerned, so
	 * {@code /submissions/**} covers it exactly as it does at request time.
	 */
	public static boolean isPublic(HttpMethod method, String path) {
		return RULES.stream().anyMatch(rule ->
				(rule.method() == null || rule.method().equals(method))
						&& MATCHER.match(rule.pattern(), path));
	}
}
