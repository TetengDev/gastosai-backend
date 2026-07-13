package com.teng.app.gastosai.config;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientIps {

    private ClientIps() {}

    public static String extract(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
