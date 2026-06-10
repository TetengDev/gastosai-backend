package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.BudgetRepository;
import com.teng.app.gastosai.repository.CategoryRepository;
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
class BudgetApiIntegrationTest {

	@Autowired
	WebApplicationContext webApplicationContext;

	@Autowired
	UserRepository userRepository;

	@Autowired
	CategoryRepository categoryRepository;

	@Autowired
	BudgetRepository budgetRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JwtUtil jwtUtil;

	ObjectMapper objectMapper = new ObjectMapper();

	MockMvc mockMvc;
	String authHeader;
	Long categoryId;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
				.apply(springSecurity())
				.build();

		budgetRepository.deleteAll();
		userRepository.deleteAll();

		User user = userRepository.save(User.builder()
				.name("Budget User")
				.email("budget@test.com")
				.password(passwordEncoder.encode("password"))
				.build());

		authHeader = "Bearer " + jwtUtil.generate(user.getEmail());

		Category cat = categoryRepository.findAll().stream()
				.filter(c -> c.getName().equals("Meal Plan"))
				.findFirst()
				.orElseGet(() -> categoryRepository.save(Category.builder().name("Meal Plan").build()));
		categoryId = cat.getId();
	}

	@Test
	void createBudget_returns201() throws Exception {
		mockMvc.perform(post("/budgets")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"categoryId": %d, "month": "2026-06", "amountLimit": 5000.00}
								""".formatted(categoryId)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.month").value("2026-06"))
				.andExpect(jsonPath("$.amountLimit").value(5000.00));
	}

	@Test
	void getBudgets_returnsCreatedBudget() throws Exception {
		mockMvc.perform(post("/budgets")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"categoryId": %d, "month": "2026-07", "amountLimit": 3000.00}
								""".formatted(categoryId)))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/budgets")
						.header("Authorization", authHeader)
						.param("month", "2026-07"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].month").value("2026-07"))
				.andExpect(jsonPath("$[0].amountLimit").value(3000.00));
	}

	@Test
	void updateBudget_updatesAmountLimit() throws Exception {
		MvcResult created = mockMvc.perform(post("/budgets")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"categoryId": %d, "month": "2026-08", "amountLimit": 4000.00}
								""".formatted(categoryId)))
				.andExpect(status().isCreated())
				.andReturn();

		var node = objectMapper.readTree(created.getResponse().getContentAsString());
		long budgetId = node.get("id").asLong();

		mockMvc.perform(put("/budgets/" + budgetId)
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"categoryId": %d, "month": "2026-08", "amountLimit": 6000.00}
								""".formatted(categoryId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.amountLimit").value(6000.00));
	}

	@Test
	void deleteBudget_returns204() throws Exception {
		MvcResult created = mockMvc.perform(post("/budgets")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"categoryId": %d, "month": "2026-09", "amountLimit": 2000.00}
								""".formatted(categoryId)))
				.andExpect(status().isCreated())
				.andReturn();

		var node = objectMapper.readTree(created.getResponse().getContentAsString());
		long budgetId = node.get("id").asLong();

		mockMvc.perform(delete("/budgets/" + budgetId)
						.header("Authorization", authHeader))
				.andExpect(status().isNoContent());

		assertThat(budgetRepository.findById(budgetId)).isEmpty();
	}

	@Test
	void getBudgetSummary_returnsCorrectStatus() throws Exception {
		mockMvc.perform(post("/budgets")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"categoryId": %d, "month": "2030-12", "amountLimit": 1000.00}
								""".formatted(categoryId)))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/budgets/summary")
						.header("Authorization", authHeader)
						.param("month", "2030-12"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.month").value("2030-12"))
				.andExpect(jsonPath("$.totalBudgeted").value(1000.00))
				.andExpect(jsonPath("$.items[0].status").value("ON_TRACK"));
	}

	@Test
	void getBudget_otherUser_returns404() throws Exception {
		MvcResult created = mockMvc.perform(post("/budgets")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"categoryId": %d, "month": "2026-10", "amountLimit": 1500.00}
								""".formatted(categoryId)))
				.andExpect(status().isCreated())
				.andReturn();

		var node = objectMapper.readTree(created.getResponse().getContentAsString());
		long budgetId = node.get("id").asLong();

		User otherUser = userRepository.save(User.builder()
				.name("Other User")
				.email("other@test.com")
				.password(passwordEncoder.encode("password"))
				.build());
		String otherAuth = "Bearer " + jwtUtil.generate(otherUser.getEmail());

		mockMvc.perform(put("/budgets/" + budgetId)
						.header("Authorization", otherAuth)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"categoryId": %d, "month": "2026-10", "amountLimit": 9999.00}
								""".formatted(categoryId)))
				.andExpect(status().isNotFound());
	}
}
