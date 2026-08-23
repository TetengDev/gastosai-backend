package com.teng.app.gastosai.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class PublicRateLimitInterceptor implements HandlerInterceptor {

    /** Provider callbacks live under here and get their own budget — see {@link #isWebhook}. */
    static final String WEBHOOK_PATH_PREFIX = "/webhooks/";

    private final int requestsPerMinute;
    private final int webhookRequestsPerMinute;
    private final RateLimiterStore store;

    public PublicRateLimitInterceptor(
            @Value("${gastos.ratelimit.public-per-minute:10}") int requestsPerMinute,
            @Value("${gastos.ratelimit.webhook-per-minute:600}") int webhookRequestsPerMinute,
            RateLimiterStore store) {
        this.requestsPerMinute = requestsPerMinute;
        this.webhookRequestsPerMinute = webhookRequestsPerMinute;
        this.store = store;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws java.io.IOException {
        String ip = ClientIps.extract(request);
        boolean webhook = isWebhook(request);
        String key = (webhook ? "pub:webhook:" : "pub:") + ip;
        if (store.tryAcquire(key, webhook ? webhookRequestsPerMinute : requestsPerMinute)) {
            return true;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":429,\"title\":\"Too Many Requests\",\"detail\":\"Rate limit exceeded. Please slow down.\"}");
        return false;
    }

    /**
     * Whether this is a provider callback rather than a request from a human.
     *
     * <p>A webhook caller is one provider hammering one path from a handful of source addresses, so
     * every event PayMongo sends lands in a single per-IP bucket. The interactive limit — ten a
     * minute, sized for a person typing a password — would shed a routine burst of genuine events,
     * and worse, shed the redelivery of the ones it just shed. So webhooks get their own budget
     * ({@code gastos.ratelimit.webhook-per-minute}, ten a second by default: far above any real
     * PayMongo burst or backlog replay, far below what an unauthenticated flood wants) under their
     * own key, so that neither traffic shape can exhaust the other's window.
     */
    private static boolean isWebhook(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return false;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.startsWith(WEBHOOK_PATH_PREFIX);
    }
}
