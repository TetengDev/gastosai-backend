package com.teng.app.gastosai.config;

import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Honors admin-only "View As" headers so an admin can preview the app as another plan tier or with
 * AI disabled. Headers are ignored entirely unless the authenticated user is an admin (no escalation).
 */
@Component
public class ViewAsInterceptor implements HandlerInterceptor {

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof User user) || !user.isAdmin()) {
			return true;
		}
		PlanKey plan = parsePlan(request.getHeader("X-View-As-Plan"));
		Boolean aiEnabled = parseAi(request.getHeader("X-View-As-Ai"));
		if (plan != null || aiEnabled != null) {
			ViewAsContext.set(plan, aiEnabled);
		}
		return true;
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
		ViewAsContext.clear();
	}

	private PlanKey parsePlan(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return PlanKey.valueOf(raw.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private Boolean parseAi(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		return "on".equalsIgnoreCase(raw.trim()) ? Boolean.TRUE : "off".equalsIgnoreCase(raw.trim()) ? Boolean.FALSE : null;
	}
}
