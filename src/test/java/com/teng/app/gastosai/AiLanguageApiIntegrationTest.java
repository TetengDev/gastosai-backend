package com.teng.app.gastosai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The picker's source of truth. It is served rather than hardcoded in each client so that adding a
 * language is a configuration change on the server, not a release of two apps — so the assertions
 * here are against the real {@code application.properties}, not a test-only set.
 *
 * <p>The user below has no AI provider key on purpose. {@code /ai/**} carries the BYO-key gate, and
 * a 402 here would mean the settings screen could not render the picker for exactly the user who
 * has not finished setting up — so these tests are also what holds the {@code WebConfig} exemption
 * in place.
 */
@SpringBootTest
class AiLanguageApiIntegrationTest extends PostgresBackedTest {

	@Autowired WebApplicationContext webApplicationContext;
	@Autowired UserRepository userRepository;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired JwtUtil jwtUtil;
	@Autowired ObjectMapper objectMapper;

	MockMvc mockMvc;
	String authHeader;

	@BeforeEach
	void setUp() {
		// JSON carries no charset parameter, and MockHttpServletResponse then falls back to
		// ISO-8859-1 — which would read 日本語 back as mojibake even though the bytes on the wire are
		// correct UTF-8. This is the assertion side of the encoding, not the server's.
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
				.apply(springSecurity())
				.defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
				.build();

		User user = userRepository.save(User.builder()
				.name("Language User")
				.email("languages@test.com")
				.password(passwordEncoder.encode("pass"))
				.role(Role.USER)
				.build());
		authHeader = "Bearer " + jwtUtil.generate(user.getEmail());
	}

	@Test
	void listsTheConfiguredLanguagesInOrderWithEnglishFirst() throws Exception {
		mockMvc.perform(get("/ai/languages").header(HttpHeaders.AUTHORIZATION, authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(11))
				.andExpect(jsonPath("$[0].code").value("en"))
				.andExpect(jsonPath("$[0].displayName").value("English"))
				.andExpect(jsonPath("$[1].code").value("fil"))
				.andExpect(jsonPath("$[1].displayName").value("Filipino"))
				.andExpect(jsonPath("$[2].code").value("ceb"))
				.andExpect(jsonPath("$[10].code").value("zh-Hans"));
	}

	/**
	 * The suite runs on {@code src/test/resources/application.properties}, which shadows the main
	 * file rather than layering on it. Without this assertion a language added to the shipped
	 * configuration and not to the test copy would leave every test above still green while the
	 * endpoint served a different list in production.
	 */
	@Test
	void theServedListMatchesTheShippedConfiguration() throws Exception {
		String body = mockMvc.perform(get("/ai/languages").header(HttpHeaders.AUTHORIZATION, authHeader))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(objectMapper.readValue(body, new TypeReference<List<Map<String, String>>>() {}))
				.as("GET /ai/languages against gastos.ai.language.supported in "
						+ "src/main/resources/application.properties — mirror any change into "
						+ "src/test/resources/application.properties, which shadows it for the suite")
				.isEqualTo(shippedLanguages());
	}

	/** {@code gastos.ai.language.supported} as the application actually ships it, in index order. */
	private static List<Map<String, String>> shippedLanguages() throws IOException {
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(
				Path.of("src/main/resources/application.properties"), StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		List<Map<String, String>> languages = new ArrayList<>();
		for (int index = 0; ; index++) {
			String code = properties.getProperty("gastos.ai.language.supported[" + index + "].code");
			if (code == null) {
				return languages;
			}
			languages.add(Map.of("code", code, "displayName",
					properties.getProperty("gastos.ai.language.supported[" + index + "].display-name")));
		}
	}

	/** Display names are written in their own language, and survive the properties encoding. */
	@Test
	void eachDisplayNameIsWrittenInItsOwnLanguage() throws Exception {
		mockMvc.perform(get("/ai/languages").header(HttpHeaders.AUTHORIZATION, authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[3].displayName").value("Español"))
				.andExpect(jsonPath("$[8].displayName").value("日本語"))
				.andExpect(jsonPath("$[9].displayName").value("한국어"))
				.andExpect(jsonPath("$[10].displayName").value("简体中文"));
	}

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/ai/languages")).andExpect(status().isUnauthorized());
	}
}
