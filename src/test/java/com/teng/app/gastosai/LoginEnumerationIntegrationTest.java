package com.teng.app.gastosai;

import com.teng.app.gastosai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Login must not reveal whether an email is registered. Both an unknown email and a
 * known email with a wrong password return the same 401 — the service runs a bcrypt
 * comparison in both branches so account existence is not observable (CWE-208).
 */
@SpringBootTest
class LoginEnumerationIntegrationTest {

	@Autowired WebApplicationContext webApplicationContext;
	@Autowired UserRepository userRepository;

	MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
		userRepository.deleteAll();
	}

	@Test
	void unknownEmail_and_wrongPassword_bothReturn401() throws Exception {
		mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Real User\",\"email\":\"real@test.com\",\"password\":\"password123\"}"))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"nobody@test.com\",\"password\":\"password123\"}"))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"real@test.com\",\"password\":\"wrongpassword\"}"))
				.andExpect(status().isUnauthorized());
	}
}
