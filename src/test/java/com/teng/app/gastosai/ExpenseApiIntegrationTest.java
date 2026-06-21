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

import java.time.LocalDate;
import java.time.YearMonth;

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
	void page_returnsPagedEnvelopeAndMetadata() throws Exception {
		for (int i = 1; i <= 3; i++) {
			mockMvc.perform(post("/expenses")
							.header("Authorization", authHeader)
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"amount\": %d.00, \"category\": \"Food\", \"date\": \"2026-04-0%dT00:00:00\", \"description\": \"e%d\"}".formatted(i, i, i)))
					.andExpect(status().isCreated());
		}

		mockMvc.perform(get("/expenses/page")
						.header("Authorization", authHeader)
						.param("page", "0")
						.param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.totalPages").value(2))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(2))
				.andExpect(jsonPath("$.last").value(false));

		mockMvc.perform(get("/expenses/page")
						.header("Authorization", authHeader)
						.param("page", "1")
						.param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.last").value(true));
	}

	@Test
	void page_capsSizeAt100() throws Exception {
		mockMvc.perform(get("/expenses/page")
						.header("Authorization", authHeader)
						.param("size", "500"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.size").value(100));
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

	@Test
	void dailyReport_returnsAllDaysInMonth() throws Exception {
		String month = "2026-03";
		int expectedDays = YearMonth.of(2026, 3).lengthOfMonth();

		mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount":150.00,"category":"Food","date":"2026-03-05T10:00:00","description":"Day5"}
								"""))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount":200.00,"category":"Food","date":"2026-03-20T10:00:00","description":"Day20"}
								"""))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/expenses/report/daily")
						.header("Authorization", authHeader)
						.param("month", month))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(expectedDays))
				.andExpect(jsonPath("$[4].date").value("2026-03-05"))
				.andExpect(jsonPath("$[4].total").value(150.00))
				.andExpect(jsonPath("$[19].date").value("2026-03-20"))
				.andExpect(jsonPath("$[19].total").value(200.00));
	}

	@Test
	void topTransactions_returnsTopByAmount() throws Exception {
		String month = "2026-04";

		mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount":50.00,"category":"Food","date":"2026-04-10T10:00:00","description":"Low"}
								"""))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount":300.00,"category":"Food","date":"2026-04-15T10:00:00","description":"High"}
								"""))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount":150.00,"category":"Food","date":"2026-04-20T10:00:00","description":"Mid"}
								"""))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/expenses/report/top")
						.header("Authorization", authHeader)
						.param("month", month)
						.param("limit", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].description").value("High"))
				.andExpect(jsonPath("$[1].description").value("Mid"));
	}

	@Test
	void dailyReport_badMonthFormat_returns400() throws Exception {
		mockMvc.perform(get("/expenses/report/daily")
						.header("Authorization", authHeader)
						.param("month", "bad-format"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void topTransactions_badMonthFormat_returns400() throws Exception {
		mockMvc.perform(get("/expenses/report/top")
						.header("Authorization", authHeader)
						.param("month", "bad-format"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void multiCurrencyExpense_isCreatedAndReturnsNormalizedBase() throws Exception {
		mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount":45,"currency":"USD","exchangeRate":57.75,"description":"Hotel","date":"2026-06-01T10:00:00"}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.currency").value("USD"))
				.andExpect(jsonPath("$.exchangeRate").value(57.750000))
				.andExpect(jsonPath("$.amountInBaseCurrency").value(2598.75));
	}
}
