package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.dto.ExpenseRequest;
import com.teng.app.gastosai.dto.ExpenseResponse;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.SubscriptionPlan;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.entity.UserSubscription;
import com.teng.app.gastosai.repository.SubscriptionPlanRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.repository.UserSubscriptionRepository;
import com.teng.app.gastosai.service.ExpenseService;
import com.teng.app.gastosai.support.PostgresBackedTest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * {@code GET /expenses/export/pdf} end to end, against a real database and the real feature gate.
 *
 * <p>Two acceptance criteria need a booted API rather than a stubbed service to be worth anything:
 * that the endpoint is gated on {@code EXPORT_PDF} — which lives in the {@code @RequiresFeature}
 * interceptor, not in the service — and that the amounts reconcile with the app, which is asserted
 * here by summing what {@code GET /expenses} serves the client and finding that number on the page.
 *
 * <p>Enforcement is switched on for this class, as in {@link EntitlementEnforcementIntegrationTest}:
 * with the flag off every plan holds every feature and the gate cannot be observed at all.
 */
@SpringBootTest
@TestPropertySource(properties = {"gastos.monetization.enforce=true", "gastos.ai.allow-shared-key=true"})
class ExpensePdfExportApiIntegrationTest extends PostgresBackedTest {

	@Autowired WebApplicationContext webApplicationContext;
	@Autowired UserRepository userRepository;
	@Autowired SubscriptionPlanRepository planRepository;
	@Autowired UserSubscriptionRepository userSubscriptionRepository;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired JwtUtil jwtUtil;
	@Autowired ExpenseService expenseService;

	MockMvc mockMvc;

	User free;
	User premium;
	String freeAuth;
	String premiumAuth;

	@BeforeEach
	void setUp() {
		mockMvc = webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
		free = createUser("Free Exporter", "free-pdf@test.com");
		premium = createUser("Premium Exporter", "premium-pdf@test.com");
		subscribePremium(premium);
		freeAuth = "Bearer " + jwtUtil.generate(free.getEmail());
		premiumAuth = "Bearer " + jwtUtil.generate(premium.getEmail());
	}

	@Test
	void freeUser_isBlockedWith402_namingExportPdf() throws Exception {
		mockMvc.perform(get("/expenses/export/pdf").header(HttpHeaders.AUTHORIZATION, freeAuth))
				.andExpect(status().isPaymentRequired())
				.andExpect(jsonPath("$.feature").value("EXPORT_PDF"));
	}

	@Test
	void freeUser_stillHasCsvExport() throws Exception {
		// The gate is on the PDF endpoint specifically; adding it must not narrow what FREE had.
		mockMvc.perform(get("/expenses/export").header(HttpHeaders.AUTHORIZATION, freeAuth))
				.andExpect(status().isOk());
	}

	@Test
	void premiumUser_getsAPdfWhoseTotalMatchesTheExpensesTheApiServes() throws Exception {
		seedExpense(premium, "1234.56", "Client workshop", "Acme Corp");
		seedExpense(premium, "89.10", "Grab to venue", "Acme Corp");
		seedExpense(premium, "0.07", "Rounding edge", null);

		byte[] pdf = mockMvc.perform(get("/expenses/export/pdf")
						.param("from", "2026-08-01")
						.param("to", "2026-08-31")
						.header(HttpHeaders.AUTHORIZATION, premiumAuth))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE))
				.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=\"expenses.pdf\""))
				.andReturn().getResponse().getContentAsByteArray();

		List<ExpenseResponse> served = expenseService.findAll(premium,
				LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), null, null);
		assertThat(served).hasSize(3);
		BigDecimal expected = served.stream()
				.map(ExpenseResponse::amountInBaseCurrency)
				.reduce(BigDecimal.ZERO, BigDecimal::add)
				.setScale(2, RoundingMode.HALF_UP);

		String text = textOf(pdf);
		assertThat(text).contains("Total (PHP)");
		assertThat(text).contains(format(expected));
		for (ExpenseResponse e : served) {
			assertThat(text).contains(e.description());
			assertThat(text).contains(format(e.amountInBaseCurrency()));
		}
	}

	@Test
	void premiumUser_canNarrowTheReportToOneTag() throws Exception {
		seedExpense(premium, "500.00", "Acme deliverable", "Acme Corp");
		seedExpense(premium, "42.00", "Unrelated coffee", null);

		Long acme = expenseService.projects(premium).stream()
				.filter(p -> "Acme Corp".equals(p.name()))
				.findFirst().orElseThrow().id();

		byte[] pdf = mockMvc.perform(get("/expenses/export/pdf")
						.param("projectId", String.valueOf(acme))
						.header(HttpHeaders.AUTHORIZATION, premiumAuth))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsByteArray();

		String text = textOf(pdf);
		assertThat(text).contains("Project / client: Acme Corp");
		assertThat(text).contains("Acme deliverable");
		assertThat(text).doesNotContain("Unrelated coffee");
		assertThat(text).contains("500.00");
	}

	@Test
	void anotherUsersExpensesNeverAppear() throws Exception {
		subscribePremium(free); // so the gate is not what keeps them out — tenancy is
		seedExpense(free, "777.00", "Someone else's dinner", null);
		seedExpense(premium, "11.00", "My own coffee", null);

		byte[] pdf = mockMvc.perform(get("/expenses/export/pdf")
						.header(HttpHeaders.AUTHORIZATION, premiumAuth))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsByteArray();

		String text = textOf(pdf);
		assertThat(text).contains("My own coffee");
		assertThat(text).doesNotContain("Someone else's dinner");
		assertThat(text).doesNotContain("777.00");
	}

	// ---------------------------------------------------------------- helpers

	private User createUser(String name, String email) {
		return userRepository.save(User.builder()
				.name(name).email(email).password(passwordEncoder.encode("pw")).role(Role.USER).build());
	}

	private void subscribePremium(User user) {
		SubscriptionPlan plan = planRepository.findByPlanKey(PlanKey.PREMIUM).orElseThrow();
		userSubscriptionRepository.save(UserSubscription.builder()
				.user(user)
				.plan(plan)
				.status(SubscriptionStatus.ACTIVE)
				.currentPeriodEnd(LocalDateTime.now().plusDays(30))
				.build());
	}

	/** Written through the service, so the rows carry exactly what the API would have stored. */
	private void seedExpense(User user, String amount, String description, String project) {
		expenseService.create(new ExpenseRequest(new BigDecimal(amount), "Business",
				LocalDateTime.of(2026, 8, 15, 10, 0), description, null, null, "PHP",
				BigDecimal.ONE, null, project), user);
	}

	private static String format(BigDecimal amount) {
		return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US)).format(amount);
	}

	private static String textOf(byte[] pdf) throws IOException {
		try (PDDocument document = Loader.loadPDF(pdf)) {
			return new PDFTextStripper().getText(document);
		}
	}
}
