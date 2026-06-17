package com.teng.app.gastosai.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns a correlation id to every request and emits one structured access log
 * per request (method, path, status, duration, user) via MDC. Runs first in the
 * chain so the id wraps everything; reads the authenticated user at completion
 * (set by {@link JwtAuthFilter}). Logs no secrets — never headers, body, or tokens.
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
	static final String REQUEST_ID_HEADER = "X-Request-Id";

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return request.getRequestURI().startsWith("/actuator");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request,
			HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {
		long startNanos = System.nanoTime();
		String incoming = request.getHeader(REQUEST_ID_HEADER);
		String requestId = (incoming != null && !incoming.isBlank()) ? incoming : UUID.randomUUID().toString();
		MDC.put("requestId", requestId);
		response.setHeader(REQUEST_ID_HEADER, requestId);
		try {
			chain.doFilter(request, response);
		} finally {
			long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
			MDC.put("method", request.getMethod());
			MDC.put("path", request.getRequestURI());
			MDC.put("status", Integer.toString(response.getStatus()));
			MDC.put("durationMs", Long.toString(durationMs));
			String user = currentUser();
			if (user != null) MDC.put("userId", user);
			log.info("http_request");
			MDC.clear();
		}
	}

	private String currentUser() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated()) return null;
		String name = auth.getName();
		return (name == null || "anonymousUser".equals(name)) ? null : name;
	}
}
