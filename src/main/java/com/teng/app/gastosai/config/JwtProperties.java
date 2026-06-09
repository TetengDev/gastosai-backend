package com.teng.app.gastosai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gastos.jwt")
public record JwtProperties(String secret, long expirationMs) {
}
