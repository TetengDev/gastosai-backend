package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.SubmissionRepository;
import com.teng.app.gastosai.repository.UserRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class SubmissionApiIntegrationTest {

	@Autowired
	WebApplicationContext webApplicationContext;

	@Autowired
	UserRepository userRepository;

	@Autowired
	SubmissionRepository submissionRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JwtUtil jwtUtil;

	MockMvc mockMvc;
	String adminHeader;
	String userHeader;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
		submissionRepository.deleteAll();
		userRepository.deleteAll();
		User admin = userRepository.save(User.builder()
				.name("Admin").email("admin@test.com").password(passwordEncoder.encode("pw")).role(Role.ADMIN).build());
		User user = userRepository.save(User.builder()
				.name("User").email("user@test.com").password(passwordEncoder.encode("pw")).role(Role.USER).build());
		adminHeader = "Bearer " + jwtUtil.generate(admin.getEmail());
		userHeader = "Bearer " + jwtUtil.generate(user.getEmail());
	}

	@Test
	void publicPost_createsSubmission() throws Exception {
		mockMvc.perform(post("/submissions")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"type\":\"CONTACT\",\"name\":\"Jane\",\"email\":\"jane@test.com\",\"message\":\"Hello there\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.type").value("CONTACT"))
				.andExpect(jsonPath("$.message").value("Hello there"))
				.andExpect(jsonPath("$.handled").value(false));

		assertThat(submissionRepository.count()).isEqualTo(1);
	}

	@Test
	void post_blankMessage_returns400() throws Exception {
		mockMvc.perform(post("/submissions")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"type\":\"SUGGESTION\",\"message\":\"   \"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void post_nullType_returns400() throws Exception {
		mockMvc.perform(post("/submissions")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"message\":\"no type\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void getAsAdmin_returnsList() throws Exception {
		mockMvc.perform(post("/submissions")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"type\":\"SUGGESTION\",\"message\":\"Add dark charts\"}"))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/submissions").header("Authorization", adminHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].message").value("Add dark charts"));
	}

	@Test
	void getAnonymous_returns401() throws Exception {
		mockMvc.perform(get("/submissions"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void getAsNonAdmin_returns403() throws Exception {
		mockMvc.perform(get("/submissions").header("Authorization", userHeader))
				.andExpect(status().isForbidden());
	}
}
