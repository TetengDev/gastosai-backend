package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.ExpenseRepository;
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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class ExpenseApiIntegrationTest {

	@Autowired
	WebApplicationContext webApplicationContext;

	@Autowired
	UserRepository userRepository;

	@Autowired
	ExpenseRepository expenseRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JwtUtil jwtUtil;

	MockMvc mockMvc;
	String authHeader;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
				.apply(springSecurity())
				.build();

		expenseRepository.deleteAll();
		userRepository.deleteAll();

		User user = userRepository.save(User.builder()
				.name("Test User")
				.email("test@test.com")
				.password(passwordEncoder.encode("password"))
				.build());

		authHeader = "Bearer " + jwtUtil.generate(user.getEmail());
	}

	@Test
	void reportPathsAreNotTreatedAsExpenseId() throws Exception {
		mockMvc.perform(get("/expenses/report/monthly")
						.header("Authorization", authHeader))
				.andExpect(status().isOk());
		mockMvc.perform(get("/expenses/report/category")
						.header("Authorization", authHeader))
				.andExpect(status().isOk());
	}

	@Test
	void createAndListExpense() throws Exception {
		mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 250.50, "category": "Food", "date": "2026-04-01T00:00:00", "description": "Lunch"}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.category").value("Food"));

		mockMvc.perform(get("/expenses")
						.header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].category").value("Food"));
	}

	@Test
	void list_dateRange_returnsFilteredExpenses() throws Exception {
		mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 100.00, "category": "Food", "date": "2026-01-15T00:00:00", "description": "Jan"}
								"""))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 200.00, "category": "Food", "date": "2026-06-15T00:00:00", "description": "Jun"}
								"""))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/expenses")
						.header("Authorization", authHeader)
						.param("from", "2026-06-01")
						.param("to", "2026-06-30"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].description").value("Jun"));
	}

	@Test
	void monthlyComparison_returnsExpectedFields() throws Exception {
		mockMvc.perform(get("/expenses/report/monthly-comparison")
						.header("Authorization", authHeader)
						.param("month", "2030-06"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.month").value("2030-06"))
				.andExpect(jsonPath("$.currentTotal").exists())
				.andExpect(jsonPath("$.previousTotal").exists());
	}

	@Test
	void export_returnsCSV() throws Exception {
		mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 150.00, "category": "Food", "date": "2026-05-10T12:00:00", "description": "Lunch export test"}
								"""))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/expenses/export")
						.header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", containsString("attachment")))
				.andExpect(content().contentTypeCompatibleWith("text/csv"))
				.andExpect(content().string(containsString("Date,Description,Category,Amount")))
				.andExpect(content().string(containsString("Lunch export test")));
	}

	@Test
	void monthlyComparison_returns400_forInvalidMonth() throws Exception {
		mockMvc.perform(get("/expenses/report/monthly-comparison")
						.header("Authorization", authHeader)
						.param("month", "invalid"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void createBusinessExpense_returnsCorrectTypeAndReimbursable() throws Exception {
		mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount":100.00,"category":"Office","date":"2026-06-01T00:00:00",
								 "description":"Laptop","expenseType":"BUSINESS","reimbursable":true}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.expenseType").value("BUSINESS"))
				.andExpect(jsonPath("$.reimbursable").value(true));
	}
}
