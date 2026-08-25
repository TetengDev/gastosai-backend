package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.ExpenseParser;
import com.teng.app.gastosai.ai.LlmResult;
import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.dto.ParsedExpenseResult;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.ExpenseSource;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TEN-175 — every expense records the route that created it, and clients can filter on it.
 *
 * <p>Written end-to-end rather than against {@code ExpenseService} alone because the claim being
 * made is about routes, not about a method: "an expense created by quick-add says QUICK_ADD" is
 * only true if the controller, the service and the column agree, and a unit test that hands the
 * service the source it then asserts proves nothing about which source the route passes.
 *
 * <p>{@code RECURRING} is absent here because nothing writes it: recurring expenses are schedules
 * that raise due alerts, and no path turns one into an {@code expenses} row. It is part of the
 * vocabulary so that path is not a contract change when it is built — see {@link ExpenseSource}.
 */
@SpringBootTest
@TestPropertySource(properties = "gastos.ai.allow-shared-key=true")
class ExpenseSourceIntegrationTest extends PostgresBackedTest {

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

	@MockitoBean
	ExpenseParser expenseParser;

	MockMvc mockMvc;
	User user;
	String authHeader;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
				.apply(springSecurity())
				.build();

		// PostgresBackedTest already truncates every application table before each test.
		user = userRepository.save(User.builder()
				.name("Source User")
				.email("source-test@test.com")
				.password(passwordEncoder.encode("password"))
				.build());
		authHeader = "Bearer " + jwtUtil.generate(user.getEmail());
	}

	private ExpenseSource storedSource(String description) {
		List<Expense> matches = expenseRepository
				.findByUserAndDescriptionContainingIgnoreCase(user, description);
		assertThat(matches).as("expense with description %s", description).hasSize(1);
		return matches.get(0).getSource();
	}

	@Test
	void manualCreateRecordsManual() throws Exception {
		mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 250.50, "category": "Food", "description": "Lunch by hand"}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.source").value("MANUAL"));

		assertThat(storedSource("Lunch by hand")).isEqualTo(ExpenseSource.MANUAL);
	}

	@Test
	void aClientMayDeclareAReceiptScan() throws Exception {
		mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 1200.00, "category": "Groceries", "description": "SM receipt",
								 "source": "RECEIPT_SCAN"}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.source").value("RECEIPT_SCAN"));

		assertThat(storedSource("SM receipt")).isEqualTo(ExpenseSource.RECEIPT_SCAN);
	}

	/**
	 * The point of the client-declarable split: a source the server decides cannot be claimed by a
	 * request body, or the field stops being evidence of anything.
	 */
	@Test
	void aClientMayNotClaimASourceTheServerDecides() throws Exception {
		mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 10.00, "description": "Smuggled import", "source": "IMPORT"}
								"""))
				.andExpect(status().isBadRequest());

		assertThat(expenseRepository.findByUserAndDescriptionContainingIgnoreCase(user, "Smuggled"))
				.isEmpty();
	}

	@Test
	void anUnknownSourceIsRejected() throws Exception {
		mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 10.00, "description": "Nonsense", "source": "TELEPATHY"}
								"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void quickAddRecordsQuickAdd() throws Exception {
		when(expenseParser.parse("tanghalian sa Jollibee, 250"))
				.thenReturn(LlmResult.ofValue(new ParsedExpenseResult(
						new BigDecimal("250.00"), "Food", LocalDateTime.of(2026, 6, 10, 12, 0),
						"Tanghalian sa Jollibee", "HIGH", true, null, null)));

		mockMvc.perform(post("/expenses/quick-add")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"text": "tanghalian sa Jollibee, 250"}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.source").value("QUICK_ADD"));

		assertThat(storedSource("Tanghalian")).isEqualTo(ExpenseSource.QUICK_ADD);
	}

	@Test
	void csvImportRecordsImport() throws Exception {
		mockMvc.perform(multipart("/expenses/import")
						.file(csv("""
								date,amount,category,description
								2026-06-15,250.00,Food,Imported lunch
								"""))
						.header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.imported").value(1));

		assertThat(storedSource("Imported lunch")).isEqualTo(ExpenseSource.IMPORT);
	}

	/** A correction changes what the expense says, never where it came from. */
	@Test
	void updateLeavesTheSourceAlone() throws Exception {
		String created = mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 500.00, "description": "Scanned receipt", "source": "RECEIPT_SCAN"}
								"""))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		long id = expenseRepository.findByUserAndDescriptionContainingIgnoreCase(user, "Scanned receipt")
				.get(0).getId();
		assertThat(created).contains("RECEIPT_SCAN");

		mockMvc.perform(put("/expenses/" + id)
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 520.00, "description": "Scanned receipt", "source": "MANUAL"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.amount").value(520.00))
				.andExpect(jsonPath("$.source").value("RECEIPT_SCAN"));

		assertThat(storedSource("Scanned receipt")).isEqualTo(ExpenseSource.RECEIPT_SCAN);
	}

	@Test
	void listAndPageFilterBySource() throws Exception {
		mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 100.00, "description": "Typed in", "date": "2026-06-02T09:00:00"}
								"""))
				.andExpect(status().isCreated());
		mockMvc.perform(multipart("/expenses/import")
						.file(csv("""
								date,amount,category,description
								2026-06-03,300.00,Food,From a file
								"""))
						.header("Authorization", authHeader))
				.andExpect(status().isOk());

		mockMvc.perform(get("/expenses").param("source", "IMPORT")
						.header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].description").value("From a file"))
				.andExpect(jsonPath("$[0].source").value("IMPORT"));

		mockMvc.perform(get("/expenses/page").param("source", "MANUAL")
						.header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].description").value("Typed in"));

		// The filter composes with the date range rather than replacing it.
		mockMvc.perform(get("/expenses")
						.param("source", "IMPORT")
						.param("from", "2026-06-01")
						.param("to", "2026-06-02")
						.header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));

		mockMvc.perform(get("/expenses")
						.param("source", "IMPORT")
						.param("from", "2026-06-03")
						.param("to", "2026-06-03")
						.header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void filteringOnAnUnknownSourceIsRejected() throws Exception {
		mockMvc.perform(get("/expenses").param("source", "TELEPATHY")
						.header("Authorization", authHeader))
				.andExpect(status().isBadRequest());
	}

	/** The v2 surface carries the same field, so a client on integer centavos is not left behind. */
	@Test
	void v2ReportsTheSourceToo() throws Exception {
		mockMvc.perform(post("/api/v2/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 25050, "category": "Food", "description": "V2 receipt",
								 "source": "RECEIPT_SCAN"}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.amount").value(25050))
				.andExpect(jsonPath("$.source").value("RECEIPT_SCAN"));

		mockMvc.perform(get("/api/v2/expenses")
						.header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].source").value("RECEIPT_SCAN"));
	}

	private static MockMultipartFile csv(String body) {
		return new MockMultipartFile("file", "expenses.csv", "text/csv",
				body.getBytes(StandardCharsets.UTF_8));
	}
}
