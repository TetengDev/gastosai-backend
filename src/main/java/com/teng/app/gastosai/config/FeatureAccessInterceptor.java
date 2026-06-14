package com.teng.app.gastosai.config;

import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.EntitlementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiresFeature} on annotated handlers before the controller runs. Authentication
 * itself is handled by Spring Security; this only adds the plan-level gate for authenticated users.
 */
@Component
@RequiredArgsConstructor
public class FeatureAccessInterceptor implements HandlerInterceptor {

    private final EntitlementService entitlementService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequiresFeature required = handlerMethod.getMethodAnnotation(RequiresFeature.class);
        if (required == null) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            return true; // unauthenticated access is rejected by Spring Security, not here
        }
        entitlementService.requireFeatureAccess(user, required.value());
        return true;
    }
}
