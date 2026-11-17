package com.teng.app.gastosai.config;

import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.GlobalExceptionHandler;
import com.teng.app.gastosai.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

	/** The same ProblemDetail {@code GlobalExceptionHandler.databaseUnavailable} answers with. */
	private static final String DATABASE_UNAVAILABLE_BODY = "{\"type\":\"about:blank\",\"title\":\"Service Unavailable\","
			+ "\"status\":503,\"detail\":\"" + GlobalExceptionHandler.DATABASE_UNAVAILABLE_DETAIL + "\"}";

	private final JwtUtil jwtUtil;
	private final UserRepository userRepository;

	@Override
	protected void doFilterInternal(HttpServletRequest request,
			HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {
		String header = request.getHeader("Authorization");
		if (header != null && header.startsWith("Bearer ")) {
			String token = header.substring(7);
			if (jwtUtil.isValid(token)) {
				String email = jwtUtil.extractEmail(token);
				if (SecurityContextHolder.getContext().getAuthentication() == null) {
					// A filter's exception never reaches GlobalExceptionHandler, so an unreachable
					// database would otherwise surface as Boot's 500 error page.
					Optional<User> found;
					try {
						found = userRepository.findByEmail(email);
					} catch (RuntimeException ex) {
						if (!GlobalExceptionHandler.isDatabaseUnreachable(ex)) {
							throw ex;
						}
						log.warn("Database unreachable on {} {}: {}", request.getMethod(), request.getRequestURI(),
								ex.getMessage());
						response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
						response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
						response.getWriter().write(DATABASE_UNAVAILABLE_BODY);
						return;
					}
					found.ifPresent(user -> {
						UsernamePasswordAuthenticationToken auth =
								new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
						auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
						SecurityContextHolder.getContext().setAuthentication(auth);
					});
				}
			}
		}
		chain.doFilter(request, response);
	}
}
