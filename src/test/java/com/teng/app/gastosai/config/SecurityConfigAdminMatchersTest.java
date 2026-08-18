package com.teng.app.gastosai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.AntPathMatcher;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the ADMIN half of the access boundary {@link PublicEndpoints#RULES} defines the other half
 * of.
 *
 * <p>TEN-203 pinned the permitted half to an explicitly-named engine but left the denied half
 * immediately below it on {@code auth.requestMatchers(String)}, so one boundary was resolved by two
 * matcher factories. They agreed, and nothing said they had to: an upgrade that changed the implicit
 * default would have moved only one side of the line between anonymous callers and the moderation
 * queue. TEN-205 routes both halves through one factory; this asserts the property, so a future
 * split shows up as a red test rather than as an open admin surface.
 */
class SecurityConfigAdminMatchersTest {

	/** How the filter chain answers a request, once the rules are walked in the chain's order. */
	private enum Access {
		ANONYMOUS, ADMIN, AUTHENTICATED
	}

	/**
	 * Paths chosen to sit on or near the boundary: the shared {@code /submissions} prefix, the
	 * prefix rules' zero-segment and deeper-nesting edges, and paths that merely start with a
	 * rule's text.
	 */
	private static final List<String> PATHS = List.of(
			"/submissions",
			"/submissions/",
			"/submissions/42",
			"/submissions/42/handled",
			"/submissionsX",
			"/admin",
			"/admin/",
			"/admin/observability",
			"/admin/chat-audit/7",
			"/adminX",
			"/expenses",
			"/auth/login",
			"/features",
			"/");

	private static final List<HttpMethod> METHODS = List.of(HttpMethod.GET, HttpMethod.POST,
			HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS);

	/**
	 * The property TEN-205 exists to hold: the factory that builds the ADMIN matchers and the one
	 * that builds the public matchers are the same factory, and answer identically.
	 *
	 * <p>Comparing the two factories directly — rather than comparing the ADMIN matchers to a
	 * hand-rolled reimplementation of them — is what makes this fail if one half is ever rebuilt on a
	 * different engine, which is the drift the issue names.
	 */
	@Test
	@DisplayName("the ADMIN matchers are built by the same factory as the public ones, over every admin pattern")
	void adminAndPublicFactoriesAgree() {
		Set<String> disagreements = new TreeSet<>();
		for (SecurityConfig.AdminRule rule : SecurityConfig.ADMIN_RULES) {
			PublicEndpoints.Rule sameRuleViaPublicFactory =
					new PublicEndpoints.Rule(rule.method(), rule.pattern());
			for (HttpMethod method : METHODS) {
				for (String path : PATHS) {
					MockHttpServletRequest request = new MockHttpServletRequest(method.name(), path);
					boolean admin = SecurityConfig.adminMatcher(rule).matches(request);
					boolean pub = SecurityConfig.publicMatcher(sameRuleViaPublicFactory).matches(request);
					if (admin != pub) {
						disagreements.add(rule.method() + " " + rule.pattern()
								+ " vs " + method + " " + path
								+ ": adminMatcher=" + admin + ", publicMatcher=" + pub);
					}
				}
			}
		}
		assertTrue(disagreements.isEmpty(),
				"The two halves of one security boundary are resolved by different engines: " + disagreements);
	}

	/**
	 * The boundary itself, not either side of it in isolation.
	 *
	 * <p>{@code POST /submissions} is the one anonymous door in a prefix that is otherwise the
	 * moderation queue; the split has to survive whichever engine resolves it, so the same table is
	 * checked against the enforced {@code PathPattern} engine and against {@code AntPathMatcher} —
	 * the engine {@link PublicEndpoints#isPublic} answers the published contract with.
	 */
	@Test
	@DisplayName("POST /submissions is anonymous while the rest of the prefix is ADMIN, under either engine")
	void boundaryHoldsUnderEitherEngine() {
		record Expected(HttpMethod method, String path, Access access) {
		}

		List<Expected> table = List.of(
				new Expected(HttpMethod.POST, "/submissions", Access.ANONYMOUS),
				new Expected(HttpMethod.GET, "/submissions", Access.ADMIN),
				new Expected(HttpMethod.PATCH, "/submissions/42/handled", Access.ADMIN),
				new Expected(HttpMethod.GET, "/submissions/42", Access.ADMIN),
				new Expected(HttpMethod.DELETE, "/submissions/42", Access.ADMIN),
				new Expected(HttpMethod.GET, "/admin/observability", Access.ADMIN),
				new Expected(HttpMethod.POST, "/submissionsX", Access.AUTHENTICATED),
				new Expected(HttpMethod.GET, "/adminX", Access.AUTHENTICATED),
				new Expected(HttpMethod.GET, "/expenses", Access.AUTHENTICATED),
				new Expected(HttpMethod.POST, "/auth/login", Access.ANONYMOUS));

		Set<String> wrong = new LinkedHashSet<>();
		for (Expected e : table) {
			Access enforced = resolveEnforced(e.method(), e.path());
			if (enforced != e.access()) {
				wrong.add(e.method() + " " + e.path() + ": expected " + e.access()
						+ ", enforced engine says " + enforced);
			}
			Access ant = resolveAnt(e.method(), e.path());
			if (ant != e.access()) {
				wrong.add(e.method() + " " + e.path() + ": expected " + e.access()
						+ ", AntPathMatcher says " + ant);
			}
		}
		assertTrue(wrong.isEmpty(), "The anonymous/ADMIN split on /submissions moved: " + wrong);
	}

	/**
	 * The whole boundary, not only the rows spelled out above: every method/path pair in the corpus
	 * must resolve the same way under both engines.
	 */
	@Test
	@DisplayName("both engines resolve every corpus request to the same access level")
	void enginesAgreeOverTheWholeCorpus() {
		Set<String> disagreements = new TreeSet<>();
		for (HttpMethod method : METHODS) {
			for (String path : PATHS) {
				Access enforced = resolveEnforced(method, path);
				Access ant = resolveAnt(method, path);
				if (enforced != ant) {
					disagreements.add(method + " " + path + ": enforced=" + enforced + ", ant=" + ant);
				}
			}
		}
		assertTrue(disagreements.isEmpty(),
				"The published contract would describe an access rule the server does not apply: "
						+ disagreements);
	}

	@Test
	@DisplayName("the admin rule list still names the surfaces the chain is meant to restrict")
	void adminRulesCoverTheAdminSurfaces() {
		Set<String> patterns = new TreeSet<>();
		SecurityConfig.ADMIN_RULES.forEach(r -> patterns.add(r.method() + " " + r.pattern()));
		// Restated 2026-08-18 for TEN-135, which publishes /api/v2 beside /api/v1. Each v1 admin
		// rule gains its v2 twin and nothing else: the same three surfaces, named twice. Confirmed
		// against the rules themselves rather than assumed — an unrestricted /api/v2/admin/** is
		// precisely the unauthenticated admin surface an earlier session stopped rather than ship.
		assertEquals(Set.of(
						"GET /submissions", "null /submissions/**", "null /admin/**",
						"GET /api/v2/submissions", "null /api/v2/submissions/**",
						"null /api/v2/admin/**"),
				patterns,
				"ADMIN_RULES changed; confirm the boundary table above still describes it.");
	}

	/** The chain's own order: public rules permit first, then the ADMIN rules, then everything else. */
	private static Access resolveEnforced(HttpMethod method, String path) {
		MockHttpServletRequest request = new MockHttpServletRequest(method.name(), path);
		for (PublicEndpoints.Rule rule : PublicEndpoints.RULES) {
			if (SecurityConfig.publicMatcher(rule).matches(request)) {
				return Access.ANONYMOUS;
			}
		}
		for (SecurityConfig.AdminRule rule : SecurityConfig.ADMIN_RULES) {
			if (SecurityConfig.adminMatcher(rule).matches(request)) {
				return Access.ADMIN;
			}
		}
		return Access.AUTHENTICATED;
	}

	/** The same walk read by {@code AntPathMatcher}, the engine the published contract is derived with. */
	private static Access resolveAnt(HttpMethod method, String path) {
		if (PublicEndpoints.isPublic(method, path)) {
			return Access.ANONYMOUS;
		}
		AntPathMatcher matcher = new AntPathMatcher();
		for (SecurityConfig.AdminRule rule : SecurityConfig.ADMIN_RULES) {
			if ((rule.method() == null || rule.method().equals(method))
					&& matcher.match(rule.pattern(), path)) {
				return Access.ADMIN;
			}
		}
		return Access.AUTHENTICATED;
	}
}
