package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class RecurringExpenseApiIntegrationTest {

	@Autowired
	WebApplicationContext webApplicationContext;

	@Autowired
	UserRepository userRepository;

	@Autowired
	RecurringExpenseRepository recurringExpenseRepository;

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

		recurringExpenseRepository.deleteAll();
		userRepository.deleteAll();

		User user = userRepository.save(User.builder()
				.name("Recurring User")
				.email("recurring@test.com")
				.password(passwordEncoder.encode("password"))
				.build());

		authHeader = "Bearer " + jwtUtil.generate(user.getEmail());
	}

	@Test
	void createRecurringExpense_returns201() throws Exception {
		mockMvc.perform(post("/recurring")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Electric Bill","amount":1500.00,"categoryName":"Utilities",
								"frequency":"MONTHLY","dayOfMonth":15}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.name").value("Electric Bill"))
				.andExpect(jsonPath("$.frequency").value("MONTHLY"))
				.andExpect(jsonPath("$.dayOfMonth").value(15));
	}

	@Test
	void getAllRecurringExpenses_returnsList() throws Exception {
		mockMvc.perform(post("/recurring")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Water Bill","amount":300.00,"categoryName":"Utilities",
								"frequency":"MONTHLY","dayOfMonth":10}
								"""))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/recurring")
						.header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("Water Bill"));
	}

	@Test
	void updateRecurringExpense_returns200() throws Exception {
		MvcResult created = mockMvc.perform(post("/recurring")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Internet","amount":999.00,"categoryName":"Utilities",
								"frequency":"MONTHLY","dayOfMonth":5}
								"""))
				.andExpect(status().isCreated())
				.andReturn();

		var node = objectMapper.readTree(created.getResponse().getContentAsString());
		long billId = node.get("id").asLong();

		mockMvc.perform(put("/recurring/" + billId)
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Internet Updated","amount":1199.00,"categoryName":"Utilities",
								"frequency":"MONTHLY","dayOfMonth":5}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Internet Updated"))
				.andExpect(jsonPath("$.amount").value(1199.00));
	}

	@Test
	void deleteRecurringExpense_returns204() throws Exception {
		MvcResult created = mockMvc.perform(post("/recurring")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Gym","amount":500.00,"categoryName":"Health",
								"frequency":"MONTHLY","dayOfMonth":1}
								"""))
				.andExpect(status().isCreated())
				.andReturn();

		var node = objectMapper.readTree(created.getResponse().getContentAsString());
		long billId = node.get("id").asLong();

		mockMvc.perform(delete("/recurring/" + billId)
						.header("Authorization", authHeader))
				.andExpect(status().isNoContent());

		assertThat(recurringExpenseRepository.findById(billId)).isEmpty();
	}

	@Test
	void getUpcoming_monthly_returns200WithDueDate() throws Exception {
		mockMvc.perform(post("/recurring")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Rent","amount":5000.00,"categoryName":"Housing",
								"frequency":"MONTHLY","dayOfMonth":15}
								"""))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/recurring/upcoming")
						.header("Authorization", authHeader)
						.param("month", "2026-06"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].dueDate").value("2026-06-15"))
				.andExpect(jsonPath("$[0].name").value("Rent"));
	}

	@Test
	void getUpcoming_invalidMonth_returns400() throws Exception {
		mockMvc.perform(get("/recurring/upcoming")
						.header("Authorization", authHeader)
						.param("month", "bad-month"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void unauthenticatedPost_returns403() throws Exception {
		mockMvc.perform(post("/recurring")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Test","amount":100.00,"frequency":"MONTHLY"}
								"""))
				.andExpect(status().isUnauthorized());
	}
}
