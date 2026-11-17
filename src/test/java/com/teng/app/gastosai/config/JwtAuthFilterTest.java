package com.teng.app.gastosai.config;

import com.teng.app.gastosai.repository.UserRepository;
import jakarta.servlet.FilterChain;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The user lookup runs in a servlet filter, before any controller, so an exception from it never
 * reaches {@code GlobalExceptionHandler} and Boot's error page renders a 500 (TEN-511).
 */
class JwtAuthFilterTest {

	private final JwtUtil jwtUtil = mock(JwtUtil.class);
	private final UserRepository userRepository = mock(UserRepository.class);
	private final FilterChain chain = mock(FilterChain.class);
	private final JwtAuthFilter filter = new JwtAuthFilter(jwtUtil, userRepository);

	private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/expenses");
	private final MockHttpServletResponse response = new MockHttpServletResponse();

	@BeforeEach
	void setUp() {
		SecurityContextHolder.clearContext();
		request.addHeader("Authorization", "Bearer token");
		when(jwtUtil.isValid("token")).thenReturn(true);
		when(jwtUtil.extractEmail("token")).thenReturn("user@test.com");
	}

	@Test
	void poolTimeoutOnUserLookup_returns503ProblemDetailAndStopsTheChain() throws Exception {
		SQLTransientConnectionException poolTimeout = new SQLTransientConnectionException(
				"HikariPool-1 - Connection is not available, request timed out after 10000ms.");
		when(userRepository.findByEmail("user@test.com")).thenThrow(new CannotCreateTransactionException(
				"Could not open JPA EntityManager for transaction",
				new JDBCConnectionException("Unable to acquire JDBC Connection", poolTimeout)));

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(503);
		assertThat(MediaType.parseMediaType(response.getContentType()))
				.isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(response.getContentAsString())
				.contains("\"status\":503").contains("\"title\":\"Service Unavailable\"")
				.doesNotContain("Exception").doesNotContain("Hikari").doesNotContain("JDBC");
		verifyNoInteractions(chain);
	}

	@Test
	void resourceFailureOnUserLookup_returns503() throws Exception {
		when(userRepository.findByEmail("user@test.com"))
				.thenThrow(new DataAccessResourceFailureException("Unable to acquire JDBC Connection"));

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(503);
		verifyNoInteractions(chain);
	}

	@Test
	void otherDataAccessFailureOnUserLookup_stillPropagates() {
		InvalidDataAccessResourceUsageException badQuery =
				new InvalidDataAccessResourceUsageException("relation \"users\" does not exist");
		when(userRepository.findByEmail("user@test.com")).thenThrow(badQuery);

		assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isSameAs(badQuery);
		verifyNoInteractions(chain);
	}
}
