package com.teng.app.gastosai.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Startup gate on {@code gastos.openai.base-url}, {@code gastos.claude.base-url} and
 * {@code gastos.paymongo.base-url}.
 *
 * <p>TEN-415 made the two AI base URLs configurable so a local run can be pointed at a dead
 * loopback port instead of reaching a provider. Nothing constrained the value, and the request
 * interceptors in {@link AIClientConfig} attach the real provider key — the managed key or a
 * decrypted BYOK key — to whatever host the property names. This check removes that: a production
 * boot accepts only {@code https://} on the provider's own host, so a redirected base URL fails the
 * boot instead of shipping keys to an operator-chosen endpoint.
 *
 * <p>TEN-423 brought {@code gastos.paymongo.base-url} under the same rule. It has the same shape
 * over a credential that moves money: {@link PayMongoRestClientConfig} attaches the live PayMongo
 * secret key as Basic auth to whatever host that property names. One implementation covers all
 * three properties so the host/scheme/port/userinfo rules cannot drift apart.
 *
 * <p>The two cases are distinguished by the <strong>active {@code prod} profile</strong>. Loopback
 * values are permitted when it is not active, which is every local run and the whole test suite;
 * they are refused when it is. The profile is the right discriminator here because
 * {@code compose.prod.yml} sets {@code SPRING_PROFILES_ACTIVE: prod} in the compose file itself,
 * not from {@code .env.prod} — so the env file this check defends against cannot also turn the
 * check off.
 *
 * <p>This is defence in depth, not a new privilege tier: the only route to setting those env vars
 * in production is editing {@code .env.prod} on the VM, and that file already holds the provider
 * keys and the PayMongo secret key in plaintext. It bounds the damage of a redirected value; it
 * does not pretend to stop someone who already has the file.
 */
@Component
@RequiredArgsConstructor
public class ProviderBaseUrlValidator {

	private static final Logger log = LoggerFactory.getLogger(ProviderBaseUrlValidator.class);

	static final String OPENAI_HOST = "api.openai.com";
	static final String CLAUDE_HOST = "api.anthropic.com";
	static final String PAYMONGO_HOST = "api.paymongo.com";

	/** Hosts that cannot leave the machine. {@code URI#getHost} keeps the brackets on IPv6. */
	private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]");

	private final Environment environment;

	@Value("${gastos.openai.base-url:https://" + OPENAI_HOST + "}")
	private String openAiBaseUrl;

	@Value("${gastos.claude.base-url:https://" + CLAUDE_HOST + "/v1}")
	private String claudeBaseUrl;

	@Value("${gastos.paymongo.base-url:https://" + PAYMONGO_HOST + "}")
	private String payMongoBaseUrl;

	@PostConstruct
	public void validate() {
		boolean loopbackAllowed = !isProdProfileActive();
		check("gastos.openai.base-url", openAiBaseUrl, OPENAI_HOST, loopbackAllowed);
		check("gastos.claude.base-url", claudeBaseUrl, CLAUDE_HOST, loopbackAllowed);
		check("gastos.paymongo.base-url", payMongoBaseUrl, PAYMONGO_HOST, loopbackAllowed);
	}

	private boolean isProdProfileActive() {
		return Arrays.asList(environment.getActiveProfiles()).contains("prod");
	}

	/**
	 * Accepts {@code https://<providerHost>} with the default port, and — only when
	 * {@code loopbackAllowed} — a loopback address on either scheme. Anything else throws, which
	 * fails the boot: a key must not be attached to a host the provider does not own.
	 *
	 * @throws IllegalStateException if the value is not permitted in this environment
	 */
	static void check(String property, String value, String providerHost, boolean loopbackAllowed) {
		if (value == null || value.isBlank()) {
			throw reject(property, value, providerHost, loopbackAllowed, "it is empty");
		}
		URI uri;
		try {
			uri = new URI(value.trim());
		} catch (URISyntaxException e) {
			throw reject(property, value, providerHost, loopbackAllowed, "it is not a valid URL");
		}
		String scheme = uri.getScheme();
		String host = uri.getHost();
		if (scheme == null || host == null) {
			throw reject(property, value, providerHost, loopbackAllowed, "it is not an absolute http(s) URL");
		}
		if (uri.getUserInfo() != null) {
			throw reject(property, value, providerHost, loopbackAllowed, "it carries userinfo");
		}
		boolean https = "https".equalsIgnoreCase(scheme);
		boolean http = "http".equalsIgnoreCase(scheme);
		if (!https && !http) {
			throw reject(property, value, providerHost, loopbackAllowed, "the scheme is neither http nor https");
		}
		// Scheme, host and port are what decide where the key goes; the path is deliberately not
		// checked, because the callers append their own paths to this prefix and a wrong path
		// reaches the provider as a 404, not a third party.
		boolean defaultPort = uri.getPort() == -1 || uri.getPort() == 443;
		if (https && host.equalsIgnoreCase(providerHost) && defaultPort) {
			return;
		}
		if (loopbackAllowed && LOOPBACK_HOSTS.contains(host.toLowerCase())) {
			log.info("{} points at {} — no request on this path can leave the machine.", property, host);
			return;
		}
		throw reject(property, value, providerHost, loopbackAllowed,
				"it is neither https://" + providerHost + " nor a permitted local address");
	}

	private static IllegalStateException reject(String property, String value, String providerHost,
			boolean loopbackAllowed, String because) {
		String permitted = loopbackAllowed
				? "https://" + providerHost + ", or a loopback address (" + String.join(", ", sortedLoopbackHosts()) + ")"
				: "https://" + providerHost + " only — loopback values are refused under the prod profile";
		return new IllegalStateException(
				"SECURITY: " + property + " is set to '" + value + "' and is refused because " + because +
				". The provider API key is attached to whatever host this names, so it must be the provider's " +
				"own host. Permitted: " + permitted + ".");
	}

	private static List<String> sortedLoopbackHosts() {
		return LOOPBACK_HOSTS.stream().sorted().toList();
	}
}
