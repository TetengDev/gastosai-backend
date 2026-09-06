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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code amountInBaseCurrency} on the v2 recurring and upcoming-bill responses (TEN-360).
 *
 * <p>The field is what lets a client render a foreign-currency bill in pesos without multiplying
 * an amount by a rate in the browser. The upcoming-bills response is the sharper case: it carries
 * no rate at all, so before this field a non-PHP bill could only be printed as its own minor units
 * behind a peso sign.
 */
@SpringBootTest
class RecurringBaseCurrencyV2ApiIntegrationTest extends PostgresBackedTest {

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
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
				.apply(springSecurity())
				.build();

		User user = userRepository.save(User.builder()
				.name("Base Currency User")
				.email("base-currency@test.com")
				.password(passwordEncoder.encode("password"))
				.build());

		authHeader = "Bearer " + jwtUtil.generate(user.getEmail());
	}

	@Test
	void foreignCurrencyRecurringExpenseCarriesTheConvertedAmount() throws Exception {
		mockMvc.perform(post("/api/v2/recurring")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Cloud Hosting","amount":2000,"categoryName":"Software",
								"frequency":"MONTHLY","dayOfMonth":5,"currency":"USD","exchangeRate":58.7500}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.amount").value(2000))
				.andExpect(jsonPath("$.amountInBaseCurrency").value(117500));

		mockMvc.perform(get("/api/v2/recurring").header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].amountInBaseCurrency").value(117500));
	}

	@Test
	void phpRecurringExpenseConvertsToItsOwnAmount() throws Exception {
		mockMvc.perform(post("/api/v2/recurring")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Rent","amount":1500000,"categoryName":"Housing",
								"frequency":"MONTHLY","dayOfMonth":1}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.currency").value("PHP"))
				.andExpect(jsonPath("$.amountInBaseCurrency").value(1500000));
	}

	@Test
	void upcomingBillsCarryTheConvertedAmount() throws Exception {
		mockMvc.perform(post("/api/v2/recurring")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Cloud Hosting","amount":2000,"categoryName":"Software",
								"frequency":"MONTHLY","dayOfMonth":5,"currency":"USD","exchangeRate":58.7500}
								"""))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/api/v2/recurring/upcoming")
						.header("Authorization", authHeader)
						.param("month", "2026-06"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].dueDate").value("2026-06-05"))
				.andExpect(jsonPath("$[0].currency").value("USD"))
				.andExpect(jsonPath("$[0].amount").value(2000))
				.andExpect(jsonPath("$[0].amountInBaseCurrency").value(117500));
	}

	@Test
	void upcomingBillInPesosConvertsToItsOwnAmount() throws Exception {
		mockMvc.perform(post("/api/v2/recurring")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Water","amount":30000,"categoryName":"Utilities",
								"frequency":"MONTHLY","dayOfMonth":10}
								"""))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/api/v2/recurring/upcoming")
						.header("Authorization", authHeader)
						.param("month", "2026-06"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].amountInBaseCurrency").value(30000));
	}

	/** A rate that lands the product on half a centavo still serves a whole one. */
	@Test
	void convertedAmountIsAWholeCentavo() throws Exception {
		mockMvc.perform(post("/api/v2/recurring")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Odd Rate","amount":333,"categoryName":"Software",
								"frequency":"MONTHLY","dayOfMonth":8,"currency":"USD","exchangeRate":1.5000}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.amountInBaseCurrency").value(500));

		mockMvc.perform(get("/api/v2/recurring/upcoming")
						.header("Authorization", authHeader)
						.param("month", "2026-06"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].amountInBaseCurrency").value(500));
	}

	/**
	 * The conversion reads the stored amount, not the two-place amount v1 displays (TEN-360
	 * review). Created through v1, which accepts the four decimals the column holds.
	 */
	@Test
	void convertsFromTheStoredAmountWhenItHasMoreThanTwoDecimals() throws Exception {
		mockMvc.perform(post("/recurring")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Fractional","amount":20.0049,"categoryName":"Software",
								"frequency":"MONTHLY","dayOfMonth":5,"currency":"USD","exchangeRate":58.7500}
								"""))
				.andExpect(status().isCreated());

		// 20.0049 x 58.75 = 1175.287875 -> 1175.2879 -> 117529 centavos. Converting from the
		// displayed 20.00 would give 117500, a 29-centavo error.
		mockMvc.perform(get("/api/v2/recurring").header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].amount").value(2000))
				.andExpect(jsonPath("$[0].amountInBaseCurrency").value(117529));

		mockMvc.perform(get("/api/v2/recurring/upcoming")
						.header("Authorization", authHeader)
						.param("month", "2026-06"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].amountInBaseCurrency").value(117529));
	}

	/** The v1 responses are untouched — neither of them gained the field. */
	@Test
	void v1ResponsesDoNotCarryTheField() throws Exception {
		mockMvc.perform(post("/recurring")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Cloud Hosting","amount":20.00,"categoryName":"Software",
								"frequency":"MONTHLY","dayOfMonth":5,"currency":"USD","exchangeRate":58.7500}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.amountInBaseCurrency").doesNotExist());

		mockMvc.perform(get("/recurring/upcoming")
						.header("Authorization", authHeader)
						.param("month", "2026-06"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].amountInBaseCurrency").doesNotExist())
				.andExpect(jsonPath("$[0].amount").value(20.00));
	}
}
