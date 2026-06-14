package com.teng.app.gastosai.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SecurityStartupValidator {

	private static final Logger log = LoggerFactory.getLogger(SecurityStartupValidator.class);
	private static final String DEV_SECRET = "gastos-dev-secret-key-change-in-production-min-32-chars";

	private final JwtProperties jwtProperties;

	@PostConstruct
	public void validate() {
		if (DEV_SECRET.equals(jwtProperties.secret())) {
			log.warn("=================================================================");
			log.warn("SECURITY: JWT_SECRET is using the default dev value.");
			log.warn("Set the JWT_SECRET environment variable before deploying to production.");
			log.warn("=================================================================");
		}
	}
}
