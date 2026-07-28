package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.SavingsGoalRepository;
import com.teng.app.gastosai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class GoalApiIntegrationTest {

	@Autowired
	WebApplicationContext webApplicationContext;

	@Autowired
	UserRepository userRepository;

	@Autowired
	SavingsGoalRepository savingsGoalRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JwtUtil jwtUtil;

	ObjectMapper objectMapper = new ObjectMapper();

	MockMvc mockMvc;
	String authHeader;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
				.apply(springSecurity())
				.build();

		savingsGoalRepository.deleteAll();
		userRepository.deleteAll();

		User user = userRepository.save(User.builder()
				.name("Goal User")
				.email("goal@test.com")
				.password(passwordEncoder.encode("password"))
				.build());

		authHeader = "Bearer " + jwtUtil.generate(user.getEmail());
	}

	@Test
	void createAndListGoal() throws Exception {
		mockMvc.perform(post("/goals")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name": "Emergency Fund", "targetAmount": 50000.00, "savedAmount": 10000.00, "targetDate": null, "paused": false}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.name").value("Emergency Fund"))
				.andExpect(jsonPath("$.targetAmount").value(50000.00))
				.andExpect(jsonPath("$.savedAmount").value(10000.00))
				.andExpect(jsonPath("$.progressPercent").value(20.00))
				.andExpect(jsonPath("$.status").value("ON_TRACK"));

		mockMvc.perform(get("/goals")
						.header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].name").value("Emergency Fund"));
	}

	/**
	 * The published contract marks {@code paused} optional, so a client is entitled to leave it out.
	 *
	 * It used to be a primitive {@code boolean} on a record, which has no field default for Jackson
	 * to fall back on — an absent value was bound as null and the request died with a 500. Goal
	 * creation was impossible for any client that trusted the spec, and every test here happened to
	 * send {@code "paused": false} explicitly, so nothing caught it.
	 *
	 * This body is deliberately minimal: only the three properties the contract actually requires.
	 */
	@Test
	void createGoal_succeedsWhenOptionalPausedIsOmitted() throws Exception {
		mockMvc.perform(post("/goals")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name": "New Laptop", "targetAmount": 60000.00, "savedAmount": 0.00}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("New Laptop"))
				// Absent must mean "not paused": a goal you just created is active.
				.andExpect(jsonPath("$.paused").value(false))
				.andExpect(jsonPath("$.status").value("ON_TRACK"));
	}

	/** Explicit null is the other shape a client can produce, and must behave the same way. */
	@Test
	void createGoal_succeedsWhenPausedIsExplicitNull() throws Exception {
		mockMvc.perform(post("/goals")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name": "Travel Fund", "targetAmount": 20000.00, "savedAmount": 0.00, "paused": null}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.paused").value(false));
	}

	@Test
	void goalStatus_completedWhenSavedReachesTarget() throws Exception {
		mockMvc.perform(post("/goals")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name": "Laptop", "targetAmount": 30000.00, "savedAmount": 30000.00, "targetDate": null, "paused": false}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.progressPercent").value(100.00));
	}

	@Test
	void goalStatus_behindWhenPastDeadline() throws Exception {
		String yesterday = LocalDate.now().minusDays(1).toString();
		mockMvc.perform(post("/goals")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name": "Vacation", "targetAmount": 20000.00, "savedAmount": 0.00, "targetDate": "%s", "paused": false}
								""".formatted(yesterday)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("BEHIND"));
	}

	@Test
	void goalStatus_onTrackWhenNoDeadline() throws Exception {
		mockMvc.perform(post("/goals")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name": "House Down Payment", "targetAmount": 500000.00, "savedAmount": 1000.00, "targetDate": null, "paused": false}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("ON_TRACK"));
	}

	@Test
	void updateGoal_updatesFields() throws Exception {
		MvcResult created = mockMvc.perform(post("/goals")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name": "Car", "targetAmount": 100000.00, "savedAmount": 5000.00, "targetDate": null, "paused": false}
								"""))
				.andExpect(status().isCreated())
				.andReturn();

		long goalId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

		mockMvc.perform(put("/goals/" + goalId)
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name": "Car Fund", "targetAmount": 100000.00, "savedAmount": 20000.00, "targetDate": null, "paused": false}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Car Fund"))
				.andExpect(jsonPath("$.savedAmount").value(20000.00));
	}

	@Test
	void deleteGoal_returns204() throws Exception {
		MvcResult created = mockMvc.perform(post("/goals")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name": "Phone", "targetAmount": 15000.00, "savedAmount": 0.00, "targetDate": null, "paused": false}
								"""))
				.andExpect(status().isCreated())
				.andReturn();

		long goalId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

		mockMvc.perform(delete("/goals/" + goalId)
						.header("Authorization", authHeader))
				.andExpect(status().isNoContent());

		assertThat(savingsGoalRepository.findById(goalId)).isEmpty();
	}
}
