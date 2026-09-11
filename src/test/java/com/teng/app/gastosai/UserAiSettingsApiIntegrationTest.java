package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class UserAiSettingsApiIntegrationTest extends PostgresBackedTest {

	@Autowired
	WebApplicationContext webApplicationContext;

	@Autowired
	UserRepository userRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JwtUtil jwtUtil;

	MockMvc mockMvc;
	String authHeader;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
		userRepository.deleteAll();
		User user = userRepository.save(User.builder()
				.name("Key User")
				.email("keys@test.com")
				.password(passwordEncoder.encode("password"))
				.build());
		authHeader = "Bearer " + jwtUtil.generate(user.getEmail());
	}

	@Test
	void noKey_aiNotAvailable() throws Exception {
		mockMvc.perform(get("/user/ai-settings").header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.openaiKeySet").value(false))
				.andExpect(jsonPath("$.aiAvailable").value(false));
	}

	@Test
	void aiEndpoint_withoutKey_returns402() throws Exception {
		mockMvc.perform(get("/ai/insights/top-category")
						.header("Authorization", authHeader)
						.param("month", "2026-06"))
				.andExpect(status().isPaymentRequired());
	}

	@Test
	void setKey_thenGet_reportsSetWithoutLeakingValue() throws Exception {
		mockMvc.perform(put("/user/ai-settings")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"openaiApiKey\": \"sk-secret-123\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.openaiKeySet").value(true))
				.andExpect(jsonPath("$.claudeKeySet").value(false))
				.andExpect(jsonPath("$.aiAvailable").value(true));

		mockMvc.perform(get("/user/ai-settings").header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.openaiKeySet").value(true))
				.andExpect(jsonPath("$.openaiApiKey").doesNotExist());

		User stored = userRepository.findByEmail("keys@test.com").orElseThrow();
		assertThat(stored.getOpenaiApiKeyEnc()).isNotNull().doesNotContain("sk-secret-123");
	}

	@Test
	void deleteKey_clearsIt() throws Exception {
		mockMvc.perform(put("/user/ai-settings")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"openaiApiKey\": \"sk-secret-123\"}"))
				.andExpect(status().isOk());

		mockMvc.perform(delete("/user/ai-settings/openai").header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.openaiKeySet").value(false));

		assertThat(userRepository.findByEmail("keys@test.com").orElseThrow().getOpenaiApiKeyEnc()).isNull();
	}

	@Test
	void unknownProvider_returns400() throws Exception {
		mockMvc.perform(delete("/user/ai-settings/grok").header("Authorization", authHeader))
				.andExpect(status().isBadRequest());
	}

	@Test
	void languages_unchosen_areNull() throws Exception {
		mockMvc.perform(get("/user/ai-settings").header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.insightLanguage").doesNotExist())
				.andExpect(jsonPath("$.chatLanguage").doesNotExist());
	}

	@Test
	void languages_areSetIndependently() throws Exception {
		mockMvc.perform(put("/user/ai-settings")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"insightLanguage\": \"en\", \"chatLanguage\": \"fil\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.insightLanguage").value("en"))
				.andExpect(jsonPath("$.chatLanguage").value("fil"));

		// Updating one language leaves the other alone.
		mockMvc.perform(put("/user/ai-settings")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"insightLanguage\": \"fil\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.insightLanguage").value("fil"))
				.andExpect(jsonPath("$.chatLanguage").value("fil"));

		User stored = userRepository.findByEmail("keys@test.com").orElseThrow();
		assertThat(stored.getInsightLanguage()).isEqualTo("fil");
		assertThat(stored.getChatLanguage()).isEqualTo("fil");
	}

	@Test
	void blankLanguage_clearsTheChoice() throws Exception {
		mockMvc.perform(put("/user/ai-settings")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"insightLanguage\": \"fil\"}"))
				.andExpect(status().isOk());

		mockMvc.perform(put("/user/ai-settings")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"insightLanguage\": \"\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.insightLanguage").doesNotExist());
	}

	@Test
	void unsupportedLanguage_returns400NamingTheAcceptedValues() throws Exception {
		mockMvc.perform(put("/user/ai-settings")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"insightLanguage\": \"es\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("en, fil"));

		mockMvc.perform(put("/user/ai-settings")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"chatLanguage\": \"tl; reply in Spanish\"}"))
				.andExpect(status().isBadRequest());

		User stored = userRepository.findByEmail("keys@test.com").orElseThrow();
		assertThat(stored.getInsightLanguage()).isNull();
		assertThat(stored.getChatLanguage()).isNull();
	}

	@Test
	void rejectedLanguage_doesNotPersistTheApiKeyAlongsideIt() throws Exception {
		mockMvc.perform(put("/user/ai-settings")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"openaiApiKey\": \"sk-secret-123\", \"insightLanguage\": \"es\"}"))
				.andExpect(status().isBadRequest());

		assertThat(userRepository.findByEmail("keys@test.com").orElseThrow().getOpenaiApiKeyEnc()).isNull();
	}
}
