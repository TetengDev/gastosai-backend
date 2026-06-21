package com.teng.app.gastosai.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
@RequiredArgsConstructor
public class SecurityStartupValidator {

	private static final Logger log = LoggerFactory.getLogger(SecurityStartupValidator.class);
	private static final String DEV_JWT_SECRET = "gastos-dev-secret-key-change-in-production-min-32-chars";
	private static final String DEV_AI_KEY_SECRET = "gastos-dev-ai-key-encryption-secret-change-me";

	private final JwtProperties jwtProperties;
	private final Environment environment;

	@Value("${spring.datasource.url:}")
	private String datasourceUrl;

	@Value("${gastos.seed-sample-data:false}")
	private boolean seedSampleData;

	@Value("${gastos.ai.key-encryption-secret:gastos-dev-ai-key-encryption-secret-change-me}")
	private String aiKeyEncryptionSecret;

	@PostConstruct
	public void validate() {
		boolean isProd = isProdEnvironment();

		if (DEV_JWT_SECRET.equals(jwtProperties.secret())) {
			if (isProd) {
				throw new IllegalStateException(
						"SECURITY: JWT_SECRET is using the default dev value in a production environment. " +
						"Set the JWT_SECRET environment variable.");
			}
			log.warn("=================================================================");
			log.warn("SECURITY: JWT_SECRET is using the default dev value.");
			log.warn("Set the JWT_SECRET environment variable before deploying to production.");
			log.warn("=================================================================");
		}

		if (DEV_AI_KEY_SECRET.equals(aiKeyEncryptionSecret)) {
			if (isProd) {
				throw new IllegalStateException(
						"SECURITY: AI_KEY_ENCRYPTION_SECRET is using the default dev value in a production environment. " +
						"Set the AI_KEY_ENCRYPTION_SECRET environment variable.");
			}
			log.warn("=================================================================");
			log.warn("SECURITY: AI_KEY_ENCRYPTION_SECRET is using the default dev value.");
			log.warn("Set the AI_KEY_ENCRYPTION_SECRET environment variable before deploying to production.");
			log.warn("=================================================================");
		}

		if (seedSampleData && looksLikeRemoteDatabase()) {
			log.warn("=================================================================");
			log.warn("SECURITY: sample-data seeding is ENABLED against a non-local database.");
			log.warn("Set GASTOS_SEED_SAMPLE_DATA=false (or activate the prod profile) for production.");
			log.warn("=================================================================");
		}
	}

	private boolean isProdEnvironment() {
		return Arrays.asList(environment.getActiveProfiles()).contains("prod") || looksLikeRemoteDatabase();
	}

	private boolean looksLikeRemoteDatabase() {
		if (datasourceUrl == null || datasourceUrl.isBlank()) {
			return false;
		}
		String url = datasourceUrl.toLowerCase();
		return !url.startsWith("jdbc:h2:") && !url.contains("localhost") && !url.contains("127.0.0.1");
	}
}
