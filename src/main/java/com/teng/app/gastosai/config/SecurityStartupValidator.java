package com.teng.app.gastosai.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SecurityStartupValidator {

	private static final Logger log = LoggerFactory.getLogger(SecurityStartupValidator.class);
	private static final String DEV_SECRET = "gastos-dev-secret-key-change-in-production-min-32-chars";

	private final JwtProperties jwtProperties;

	@Value("${spring.datasource.url:}")
	private String datasourceUrl;

	@Value("${gastos.seed-sample-data:false}")
	private boolean seedSampleData;

	@PostConstruct
	public void validate() {
		if (DEV_SECRET.equals(jwtProperties.secret())) {
			log.warn("=================================================================");
			log.warn("SECURITY: JWT_SECRET is using the default dev value.");
			log.warn("Set the JWT_SECRET environment variable before deploying to production.");
			log.warn("=================================================================");
		}
		if (seedSampleData && looksLikeRemoteDatabase()) {
			log.warn("=================================================================");
			log.warn("SECURITY: sample-data seeding is ENABLED against a non-local database.");
			log.warn("Set GASTOS_SEED_SAMPLE_DATA=false (or activate the prod profile) for production.");
			log.warn("=================================================================");
		}
	}

	private boolean looksLikeRemoteDatabase() {
		if (datasourceUrl == null || datasourceUrl.isBlank()) {
			return false;
		}
		String url = datasourceUrl.toLowerCase();
		return !url.startsWith("jdbc:h2:") && !url.contains("localhost") && !url.contains("127.0.0.1");
	}
}
