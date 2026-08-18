package com.teng.app.gastosai.config;

import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.Arrays;
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

	/**
	 * The URL prefixes the versioned API is served under, in the order they were introduced.
	 *
	 * <p>{@code ""} is v1, and it is empty rather than {@code "/api/v1"} because that is where v1
	 * actually lives: the original controllers are mapped at the root — {@code /expenses},
	 * {@code /auth/login} — and TEN-135 requires v1 to stay byte-identical, so moving it under a
	 * prefix was not an option. "v1" is the name of that surface, not a path segment on it.
	 *
	 * <p>Every consumer that has to cover both surfaces — the security matchers here and in
	 * {@link SecurityConfig}, the path-pattern interceptors in {@code WebConfig} — expands its
	 * patterns over this list rather than writing the {@code /api/v2} half out by hand. A v3 is then
	 * one entry, and more importantly a rule cannot be added to one surface and forgotten on the
	 * other, which on this class would mean an endpoint anonymous on v1 and 401 on v2.
	 */
	public static final List<String> VERSION_PREFIXES = List.of("", "/api/v2");

	/**
	 * Public rules that exist on every version of the API, written once at the v1 path and expanded
	 * over {@link #VERSION_PREFIXES} into {@link #RULES}.
	 */
	private static final List<Rule> VERSIONED_RULES = List.of(
			new Rule(HttpMethod.POST, "/auth/register"),
			new Rule(HttpMethod.POST, "/auth/login"),
			new Rule(HttpMethod.POST, "/auth/magic-link"),
			new Rule(HttpMethod.POST, "/auth/magic-link/verify"),
			new Rule(HttpMethod.POST, "/auth/google"),
			new Rule(null, "/features"),
			new Rule(HttpMethod.POST, "/submissions"),
			new Rule(HttpMethod.GET, "/subscription/pricing"));

	/**
	 * Public rules that are not part of the versioned API and so are never prefixed.
	 *
	 * <p>{@code /webhooks/paymongo} sits here rather than above deliberately: it has no v2 twin
	 * (see the {@code controller.v2} package javadoc), because PayMongo calls a URL configured in
	 * their dashboard rather than a base URL a client repoints. Prefixing it would open a second
	 * anonymous entry point with no caller. The rest — actuator, the error dispatch, Swagger UI and
	 * the spec itself — are infrastructure, not API surface.
	 */
	private static final List<Rule> UNVERSIONED_RULES = List.of(
			new Rule(HttpMethod.OPTIONS, "/**"),
			new Rule(null, "/actuator/info"),
			new Rule(null, "/actuator/health"),
			new Rule(null, "/error"),
			new Rule(null, "/swagger-ui/**"),
			new Rule(null, "/swagger-ui.html"),
			new Rule(null, "/v3/api-docs/**"),
			new Rule(HttpMethod.POST, "/webhooks/paymongo"));

	public static final List<Rule> RULES = buildRules();

	private static List<Rule> buildRules() {
		List<Rule> rules = new ArrayList<>(UNVERSIONED_RULES);
		for (String prefix : VERSION_PREFIXES) {
			for (Rule rule : VERSIONED_RULES) {
				rules.add(new Rule(rule.method(), prefix + rule.pattern()));
			}
		}
		return List.copyOf(rules);
	}

	/**
	 * Each of {@code patterns} at every version prefix, flattened — for a caller that has to
	 * register one set of path patterns across both surfaces, which in practice means the
	 * interceptor registrations in {@code WebConfig}.
	 */
	public static String[] atEveryVersion(String... patterns) {
		return Arrays.stream(patterns)
				.flatMap(pattern -> VERSION_PREFIXES.stream().map(prefix -> prefix + pattern))
				.toArray(String[]::new);
	}

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
