package com.teng.app.gastosai.config;

import com.teng.app.gastosai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import org.springframework.http.HttpStatus;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private final JwtAuthFilter jwtAuthFilter;
	private final RequestLoggingFilter requestLoggingFilter;
	private final UserRepository userRepository;

	/**
	 * A request restricted to {@code ROLE_ADMIN}. A {@code null} method means every method on the
	 * pattern is restricted.
	 */
	record AdminRule(HttpMethod method, String pattern) {
	}

	/**
	 * The ADMIN half of the boundary {@link PublicEndpoints#RULES} defines the other half of.
	 *
	 * <p>Order matters against that list, not within itself: the chain permits every public rule
	 * first, so {@code POST /submissions} stays anonymous even though {@code /submissions/**} is
	 * restricted here.
	 */
	static final List<AdminRule> ADMIN_RULES = List.of(
			new AdminRule(HttpMethod.GET, "/submissions"),
			new AdminRule(null, "/submissions/**"),
			new AdminRule(null, "/admin/**"));

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.csrf(AbstractHttpConfigurer::disable)
				.cors(Customizer.withDefaults())
				.headers(h -> h
						.httpStrictTransportSecurity(hsts -> hsts
								.maxAgeInSeconds(31536000)
								.includeSubDomains(true)
								.requestMatcher(request -> true))
						.contentSecurityPolicy(csp -> csp
								.policyDirectives("default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; frame-ancestors 'none'"))
						.frameOptions(fo -> fo.deny())
						.referrerPolicy(rp -> rp
								.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> {
					// Every permitAll matcher comes from PublicEndpoints.RULES, which is also what
					// decides whether an operation carries a `security` block in the published
					// contract. One list, so the spec cannot claim an endpoint is open once this
					// chain stops permitting it.
					for (PublicEndpoints.Rule rule : PublicEndpoints.RULES) {
						auth.requestMatchers(publicMatcher(rule)).permitAll();
					}
					// The denied half of the same boundary, built through the same factory as the
					// permitted half above. Two matcher engines resolving one boundary is the drift
					// this avoids: an upgrade that moved the implicit default would move only one
					// side of the line between anonymous callers and the moderation queue.
					for (AdminRule rule : ADMIN_RULES) {
						auth.requestMatchers(adminMatcher(rule)).hasRole("ADMIN");
					}
					auth.anyRequest().authenticated();
				})
				.exceptionHandling(e -> e
						.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
				.authenticationProvider(authenticationProvider())
				.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterBefore(requestLoggingFilter, JwtAuthFilter.class);
		return http.build();
	}

	/**
	 * The request matcher this chain permits a {@link PublicEndpoints.Rule} with.
	 *
	 * <p>Built explicitly rather than through {@code auth.requestMatchers(String)} so the engine is
	 * named in the source instead of inherited from whatever Spring Security's default happens to be
	 * on the next upgrade. That matters because {@link PublicEndpoints#isPublic} answers the same
	 * question with {@code AntPathMatcher} — it has to, since it is handed OpenAPI path templates
	 * rather than live requests — and two engines silently disagreeing is exactly the drift this is
	 * meant to prevent. {@code PublicEndpointsTest.matchesTheEngineSecurityConfigUses} asserts the
	 * two agree over every pattern in {@code RULES}; pinning the engine here is what gives that test
	 * something stable to compare against.
	 */
	static RequestMatcher publicMatcher(PublicEndpoints.Rule rule) {
		return matcher(rule.method(), rule.pattern());
	}

	/**
	 * The request matcher this chain restricts an {@link AdminRule} with.
	 *
	 * <p>Deliberately the same factory as {@link #publicMatcher}, and named for the same reason. The
	 * permitted and denied halves of one boundary have to be resolved by one engine: if they were not,
	 * an upgrade that changed the implicit default of {@code auth.requestMatchers(String)} would move
	 * only the ADMIN half — on the line separating anonymous callers from the moderation queue and the
	 * admin surface. {@code SecurityConfigAdminMatchersTest} asserts the two factories agree over
	 * every admin pattern, and that the boundary they jointly define resolves the same way under
	 * either engine.
	 */
	static RequestMatcher adminMatcher(AdminRule rule) {
		return matcher(rule.method(), rule.pattern());
	}

	/** The one place a matcher engine is chosen, for both halves of the boundary. */
	private static RequestMatcher matcher(HttpMethod method, String pattern) {
		PathPatternRequestMatcher.Builder builder = PathPatternRequestMatcher.withDefaults();
		return method == null ? builder.matcher(pattern) : builder.matcher(method, pattern);
	}

	@Bean
	public UserDetailsService userDetailsService() {
		return email -> userRepository.findByEmail(email)
				.orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
	}

	@Bean
	public AuthenticationProvider authenticationProvider() {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService());
		provider.setPasswordEncoder(passwordEncoder());
		return provider;
	}

	@Bean
	public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
		return config.getAuthenticationManager();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

}
