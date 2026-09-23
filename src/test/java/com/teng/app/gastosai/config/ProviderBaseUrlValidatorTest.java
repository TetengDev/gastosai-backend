package com.teng.app.gastosai.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The provider base URLs are configuration (TEN-415), and the request interceptors attach the real
 * provider key to whatever host they name. These tests pin both directions of the gate: the real
 * host and the documented loopback value boot, an arbitrary external host does not, and under the
 * {@code prod} profile the loopback value is refused too.
 *
 * <p>Nothing here opens a socket — the validator only inspects strings.
 */
class ProviderBaseUrlValidatorTest {

	private static final String OPENAI = "gastos.openai.base-url";
	private static final String OPENAI_HOST = "api.openai.com";
	private static final String CLAUDE_HOST = "api.anthropic.com";

	private static void checkLocal(String value) {
		ProviderBaseUrlValidator.check(OPENAI, value, OPENAI_HOST, true);
	}

	private static void checkProd(String value) {
		ProviderBaseUrlValidator.check(OPENAI, value, OPENAI_HOST, false);
	}

	@Nested
	class Accepted {

		@Test
		void theRealProviderHostOverHttps() {
			assertThatCode(() -> checkProd("https://" + OPENAI_HOST)).doesNotThrowAnyException();
			assertThatCode(() -> checkLocal("https://" + OPENAI_HOST)).doesNotThrowAnyException();
		}

		@Test
		void theRealProviderHostWithAPathAndAnExplicitPort() {
			assertThatCode(() -> checkProd("https://" + OPENAI_HOST + ":443/v1")).doesNotThrowAnyException();
		}

		@Test
		void theDocumentedLoopbackValueOutsideProd() {
			assertThatCode(() -> checkLocal("http://127.0.0.1:9")).doesNotThrowAnyException();
			assertThatCode(() -> checkLocal("http://localhost:9")).doesNotThrowAnyException();
			assertThatCode(() -> checkLocal("http://[::1]:9")).doesNotThrowAnyException();
		}

		@Test
		void aLoopbackServerTheTestSuiteStartsItself() {
			assertThatCode(() -> checkLocal("http://127.0.0.1:54321/v1")).doesNotThrowAnyException();
		}
	}

	@Nested
	class Refused {

		@Test
		void anArbitraryExternalHost() {
			assertThatThrownBy(() -> checkLocal("https://evil.example.com"))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining(OPENAI)
					.hasMessageContaining("https://" + OPENAI_HOST);
			assertThatThrownBy(() -> checkProd("https://evil.example.com"))
					.isInstanceOf(IllegalStateException.class);
		}

		@Test
		void aHostThatMerelyLooksLikeTheProviderHost() {
			assertThatThrownBy(() -> checkProd("https://api.openai.com.evil.example.com"))
					.isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> checkProd("https://api-openai.com"))
					.isInstanceOf(IllegalStateException.class);
		}

		@Test
		void theOtherProvidersHost() {
			assertThatThrownBy(() -> checkProd("https://" + CLAUDE_HOST + "/v1"))
					.isInstanceOf(IllegalStateException.class);
		}

		@Test
		void plainHttpOnTheProviderHost() {
			assertThatThrownBy(() -> checkProd("http://" + OPENAI_HOST))
					.isInstanceOf(IllegalStateException.class);
		}

		@Test
		void aNonDefaultPortOnTheProviderHost() {
			assertThatThrownBy(() -> checkProd("https://" + OPENAI_HOST + ":8443"))
					.isInstanceOf(IllegalStateException.class);
		}

		@Test
		void userinfoThatWouldChangeWhoIsAuthenticatedTo() {
			assertThatThrownBy(() -> checkProd("https://attacker@" + OPENAI_HOST))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("userinfo");
		}

		@Test
		void aLoopbackValueUnderProd() {
			assertThatThrownBy(() -> checkProd("http://127.0.0.1:9"))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("loopback values are refused under the prod profile");
		}

		@Test
		void aNonHttpScheme() {
			assertThatThrownBy(() -> checkProd("file:///etc/passwd"))
					.isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> checkLocal("ftp://127.0.0.1"))
					.isInstanceOf(IllegalStateException.class);
		}

		@Test
		void aValueThatIsNotAUrlAtAll() {
			assertThatThrownBy(() -> checkProd("api.openai.com")).isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> checkProd("")).isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> checkProd("h ttp://x")).isInstanceOf(IllegalStateException.class);
		}
	}

	@Nested
	class WiredValidator {

		private ProviderBaseUrlValidator validator(String[] profiles, String openAi, String claude) {
			MockEnvironment environment = new MockEnvironment();
			environment.setActiveProfiles(profiles);
			ProviderBaseUrlValidator validator = new ProviderBaseUrlValidator(environment);
			ReflectionTestUtils.setField(validator, "openAiBaseUrl", openAi);
			ReflectionTestUtils.setField(validator, "claudeBaseUrl", claude);
			return validator;
		}

		@Test
		void shippedDefaultsBoot() {
			assertThatCode(validator(new String[] {"prod"}, "https://" + OPENAI_HOST, "https://" + CLAUDE_HOST + "/v1")::validate)
					.doesNotThrowAnyException();
		}

		@Test
		void theDocumentedLocalRunBoots() {
			assertThatCode(validator(new String[] {}, "http://127.0.0.1:9", "http://127.0.0.1:9/v1")::validate)
					.doesNotThrowAnyException();
		}

		@Test
		void aRedirectedClaudeUrlFailsTheBoot() {
			assertThatThrownBy(validator(new String[] {"prod"}, "https://" + OPENAI_HOST, "https://collector.example.com/v1")::validate)
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("gastos.claude.base-url");
		}

		@Test
		void aRedirectedOpenAiUrlFailsTheBootOutsideProdToo() {
			assertThatThrownBy(validator(new String[] {}, "https://collector.example.com", "http://127.0.0.1:9/v1")::validate)
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("gastos.openai.base-url");
		}
	}
}
