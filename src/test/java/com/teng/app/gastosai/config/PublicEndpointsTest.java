package com.teng.app.gastosai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link PublicEndpoints#isPublic} — the single list that both {@link SecurityConfig} and
 * {@link OpenApiConfig} read.
 *
 * <p>Before this existed the list was only exercised indirectly: the filter chain enforced it and
 * the published spec described it, but nothing asserted the two readings of a rule were the same
 * reading. That is the drift TEN-202 shipped a shared list to prevent and this test is what makes
 * the prevention hold.
 */
class PublicEndpointsTest {

	/**
	 * A concrete request, the answer expected of it, and the {@code RULES} pattern it exists to
	 * cover. The pattern is carried so {@link #coversEveryRule()} can prove the table did not fall
	 * behind the list.
	 */
	private record Case(HttpMethod method, String path, String coversPattern) {
	}

	/** One representative request per rule in {@link PublicEndpoints#RULES}, in the same order. */
	private static final List<Case> PUBLIC_CASES = List.of(
			new Case(HttpMethod.POST, "/auth/register", "/auth/register"),
			new Case(HttpMethod.POST, "/auth/login", "/auth/login"),
			new Case(HttpMethod.POST, "/auth/magic-link", "/auth/magic-link"),
			new Case(HttpMethod.POST, "/auth/magic-link/verify", "/auth/magic-link/verify"),
			new Case(HttpMethod.POST, "/auth/google", "/auth/google"),
			new Case(HttpMethod.OPTIONS, "/expenses", "/**"),
			new Case(HttpMethod.GET, "/actuator/info", "/actuator/info"),
			new Case(HttpMethod.GET, "/actuator/health", "/actuator/health"),
			new Case(HttpMethod.GET, "/features", "/features"),
			new Case(HttpMethod.GET, "/error", "/error"),
			new Case(HttpMethod.GET, "/swagger-ui/index.html", "/swagger-ui/**"),
			new Case(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui.html"),
			new Case(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**"),
			new Case(HttpMethod.POST, "/submissions", "/submissions"),
			new Case(HttpMethod.POST, "/webhooks/paymongo", "/webhooks/paymongo"),
			new Case(HttpMethod.GET, "/subscription/pricing", "/subscription/pricing"),

			// TEN-135 publishes /api/v2 alongside /api/v1. A v2 path that serves the same resource
			// must be public on exactly the same terms — anything else means the version bump
			// quietly changed who can reach it, which is the one thing a parallel surface must not
			// do. Mirrored one-for-one from the block above; this test failing is how a v2 rule
			// added without a case here gets caught.
			new Case(HttpMethod.POST, "/api/v2/auth/register", "/api/v2/auth/register"),
			new Case(HttpMethod.POST, "/api/v2/auth/login", "/api/v2/auth/login"),
			new Case(HttpMethod.POST, "/api/v2/auth/magic-link", "/api/v2/auth/magic-link"),
			new Case(HttpMethod.POST, "/api/v2/auth/magic-link/verify", "/api/v2/auth/magic-link/verify"),
			new Case(HttpMethod.POST, "/api/v2/auth/google", "/api/v2/auth/google"),
			new Case(HttpMethod.GET, "/api/v2/features", "/api/v2/features"),
			new Case(HttpMethod.POST, "/api/v2/submissions", "/api/v2/submissions"),
			new Case(HttpMethod.GET, "/api/v2/subscription/pricing", "/api/v2/subscription/pricing"));

	/**
	 * Requests that must not read as public. The first two are the ADMIN surface that sits on the
	 * same path prefix as the one genuinely anonymous submission endpoint — the place a careless
	 * widening of {@code /submissions} to {@code /submissions/**} would open the moderation queue to
	 * the internet. The third is the method-mismatch case: a rule names POST, so GET on the same
	 * path is not covered by it.
	 */
	private static final List<Case> NON_PUBLIC_CASES = List.of(
			new Case(HttpMethod.GET, "/submissions", null),
			new Case(HttpMethod.PATCH, "/submissions/{id}/handled", null),
			new Case(HttpMethod.PATCH, "/submissions/42/handled", null),
			new Case(HttpMethod.GET, "/auth/login", null),
			new Case(HttpMethod.GET, "/expenses", null),
			new Case(HttpMethod.POST, "/expenses", null),
			new Case(HttpMethod.GET, "/admin/observability", null),
			new Case(HttpMethod.GET, "/subscription/status", null),
			new Case(HttpMethod.POST, "/auth/logout", null));

	@Test
	@DisplayName("every rule in RULES permits its representative request")
	void permitsOneRepresentativeRequestPerRule() {
		Set<String> missed = new TreeSet<>();
		for (Case c : PUBLIC_CASES) {
			if (!PublicEndpoints.isPublic(c.method(), c.path())) {
				missed.add(c.method() + " " + c.path() + " (rule " + c.coversPattern() + ")");
			}
		}
		assertTrue(missed.isEmpty(), "Rules that no longer permit their representative request: " + missed);
	}

	@Test
	@DisplayName("the representative table covers every rule, so a new rule cannot arrive untested")
	void coversEveryRule() {
		Set<String> patterns = PublicEndpoints.RULES.stream()
				.map(PublicEndpoints.Rule::pattern)
				.collect(Collectors.toCollection(TreeSet::new));
		Set<String> covered = PUBLIC_CASES.stream()
				.map(Case::coversPattern)
				.collect(Collectors.toCollection(TreeSet::new));

		Set<String> uncovered = new TreeSet<>(patterns);
		uncovered.removeAll(covered);
		assertTrue(uncovered.isEmpty(),
				"PublicEndpoints.RULES gained patterns with no case in PUBLIC_CASES: " + uncovered);

		Set<String> stale = new TreeSet<>(covered);
		stale.removeAll(patterns);
		assertTrue(stale.isEmpty(),
				"PUBLIC_CASES names patterns that are no longer in PublicEndpoints.RULES: " + stale);
	}

	@Test
	@DisplayName("the ADMIN submission surface and a method mismatch do not read as public")
	void rejectsRequestsNoRuleCovers() {
		Set<String> leaked = new TreeSet<>();
		for (Case c : NON_PUBLIC_CASES) {
			if (PublicEndpoints.isPublic(c.method(), c.path())) {
				leaked.add(c.method() + " " + c.path());
			}
		}
		assertTrue(leaked.isEmpty(),
				"These require authentication but PublicEndpoints reports them anonymous: " + leaked);
	}

	@Test
	@DisplayName("a {…} placeholder is an ordinary segment, so path templates answer as their requests do")
	void treatsOpenApiPathTemplatesAsRequestPaths() {
		// OpenApiConfig calls isPublic with the spec's templated paths, not with live request paths.
		// A template must therefore give the same answer its concrete instances give, or the
		// published contract would disagree with the running server on exactly the paths that carry
		// an id.
		assertEquals(
				PublicEndpoints.isPublic(HttpMethod.PATCH, "/submissions/42/handled"),
				PublicEndpoints.isPublic(HttpMethod.PATCH, "/submissions/{id}/handled"),
				"A templated path must resolve the same way as a concrete one.");
		assertFalse(PublicEndpoints.isPublic(HttpMethod.GET, "/expenses/{id}"));
		assertTrue(PublicEndpoints.isPublic(HttpMethod.OPTIONS, "/expenses/{id}"));
	}

	/**
	 * The matcher-engine question, settled by assertion rather than by assumption.
	 *
	 * <p>{@code isPublic} matches with {@code AntPathMatcher} because it is handed OpenAPI path
	 * templates, not {@code HttpServletRequest}s. {@link SecurityConfig} matches live requests with
	 * {@code PathPatternRequestMatcher}. Two engines reading one list is only safe while they agree,
	 * and nothing in the type system says they must — so this walks every pattern in {@code RULES}
	 * against a corpus of concrete requests and fails on the first divergence.
	 */
	@Test
	@DisplayName("isPublic agrees with the engine SecurityConfig enforces with, over every rule")
	void matchesTheEngineSecurityConfigUses() {
		Set<String> paths = new LinkedHashSet<>();
		PUBLIC_CASES.forEach(c -> paths.add(c.path()));
		NON_PUBLIC_CASES.stream().map(Case::path).filter(p -> !p.contains("{")).forEach(paths::add);
		// Boundaries the representative cases do not reach: a prefix rule matching zero trailing
		// segments, a deeper nesting under one, and a path that merely starts with a rule's text.
		paths.addAll(List.of(
				"/swagger-ui",
				"/swagger-ui/",
				"/swagger-ui/swagger-ui-bundle.js",
				"/v3/api-docs/swagger-config",
				"/v3/api-docsX",
				"/submissions/",
				"/submissions/42",
				"/auth/magic-link/verify/extra",
				"/features/flags",
				"/"));

		List<HttpMethod> methods = List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
				HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS);

		Set<String> disagreements = new TreeSet<>();
		for (PublicEndpoints.Rule rule : PublicEndpoints.RULES) {
			for (HttpMethod method : methods) {
				for (String path : paths) {
					boolean ant = matchesAnt(rule, method, path);
					boolean enforced = SecurityConfig.publicMatcher(rule)
							.matches(new MockHttpServletRequest(method.name(), path));
					if (ant != enforced) {
						disagreements.add(rule.method() + " " + rule.pattern()
								+ " vs " + method + " " + path
								+ ": isPublic=" + ant + ", SecurityConfig=" + enforced);
					}
				}
			}
		}

		assertTrue(disagreements.isEmpty(),
				"AntPathMatcher and the enforced PathPattern engine disagree; the published contract "
						+ "would describe an access rule the server does not apply: " + disagreements);
	}

	/** {@link PublicEndpoints#isPublic} narrowed to a single rule, so a mismatch names that rule. */
	private static boolean matchesAnt(PublicEndpoints.Rule rule, HttpMethod method, String path) {
		return (rule.method() == null || rule.method().equals(method))
				&& new org.springframework.util.AntPathMatcher().match(rule.pattern(), path);
	}
}
