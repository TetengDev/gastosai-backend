package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.ExpenseParser;
import com.teng.app.gastosai.ai.LlmResult;
import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.dto.ParsedExpenseResult;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /expenses/quick-add} — parse and save in one call.
 *
 * <p>The parser is mocked, as everywhere else in this suite: these tests are about what the
 * endpoint does with a draft, not about the model's Taglish comprehension, which
 * {@code ClaudeExpenseParserTest} and {@code OpenAiExpenseParserTest} cover against golden fixtures.
 * The inputs here are still Filipino and Taglish so the wiring is exercised with the text it will
 * actually see.
 */
@SpringBootTest
@TestPropertySource(properties = "gastos.ai.allow-shared-key=true")
class ExpenseQuickAddIntegrationTest extends PostgresBackedTest {

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
                .name("Quick Add User")
                .email("quick-add-test@test.com")
                .password(passwordEncoder.encode("password"))
                .build());
        authHeader = "Bearer " + jwtUtil.generate(user.getEmail());
    }

    private static ParsedExpenseResult saveable(BigDecimal amount, String category, String description) {
        return new ParsedExpenseResult(amount, category, LocalDateTime.of(2026, 6, 10, 12, 0),
                description, "HIGH", true, null, null);
    }

    @Test
    void savesTheExpenseAndReturnsTheSavedRecord() throws Exception {
        when(expenseParser.parse("bumili ako ng tanghalian sa Jollibee, 250 pesos"))
                .thenReturn(LlmResult.ofValue(
                        saveable(new BigDecimal("250.00"), "Meal Plan", "Tanghalian sa Jollibee")));

        mockMvc.perform(post("/expenses/quick-add")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "bumili ako ng tanghalian sa Jollibee, 250 pesos"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.amount").value(250.00))
                .andExpect(jsonPath("$.category").value("Meal Plan"))
                .andExpect(jsonPath("$.description").value("Tanghalian sa Jollibee"))
                .andExpect(jsonPath("$.currency").value("PHP"))
                .andExpect(jsonPath("$.expenseType").value("PERSONAL"));

        assertThat(expenseRepository.findAllByUserOrderByDateDesc(user)).hasSize(1);
    }

    @Test
    void savesTaglishInputWithTheParsedTimestamp() throws Exception {
        when(expenseParser.parse("nag-Grab ako kanina papuntang office, 180"))
                .thenReturn(LlmResult.ofValue(
                        saveable(new BigDecimal("180.00"), "Transportation", "Grab papuntang office")));

        mockMvc.perform(post("/expenses/quick-add")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "nag-Grab ako kanina papuntang office, 180"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(180.00))
                .andExpect(jsonPath("$.category").value("Transportation"))
                // Served with the explicit +08:00 offset every other API timestamp carries.
                .andExpect(jsonPath("$.date").value("2026-06-10T12:00:00+08:00"));
    }

    @Test
    void aParseThatFailsSavesNothingAndSaysWhy() throws Exception {
        when(expenseParser.parse("may binayad ako kanina")).thenReturn(LlmResult.ofValue(
                new ParsedExpenseResult(BigDecimal.ZERO, "Uncategorized",
                        LocalDateTime.of(2026, 6, 10, 0, 0), "Payment, details not specified",
                        "LOW", false, "Magkano at para saan? Pakidagdag ang detalye.", null)));

        mockMvc.perform(post("/expenses/quick-add")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "may binayad ako kanina"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Magkano at para saan? Pakidagdag ang detalye."));

        assertThat(expenseRepository.findAllByUserOrderByDateDesc(user)).isEmpty();
    }

    @Test
    void aDraftClaimingSaveableWithNoAmountIsStillRejected() throws Exception {
        when(expenseParser.parse("kumain kami sa labas kahapon")).thenReturn(LlmResult.ofValue(
                new ParsedExpenseResult(null, "Meal Plan", LocalDateTime.of(2026, 6, 9, 12, 0),
                        "Kumain sa labas", "HIGH", true, null, null)));

        mockMvc.perform(post("/expenses/quick-add")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "kumain kami sa labas kahapon"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Could not read an amount from that text."));

        assertThat(expenseRepository.findAllByUserOrderByDateDesc(user)).isEmpty();
    }

    @Test
    void rejectsBlankText() throws Exception {
        mockMvc.perform(post("/expenses/quick-add")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": ""}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(expenseRepository.findAllByUserOrderByDateDesc(user)).isEmpty();
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/expenses/quick-add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "kape 75 pesos"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    /** The two-step path this endpoint is an alternative to, not a replacement for. */
    @Test
    void theTwoStepParsePathStillReturnsADraftWithoutSaving() throws Exception {
        when(expenseParser.parse("kape 75 pesos")).thenReturn(LlmResult.ofValue(
                saveable(new BigDecimal("75.00"), "Meal Plan", "Kape")));

        mockMvc.perform(post("/expenses/parse")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "kape 75 pesos"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saveable").value(true))
                .andExpect(jsonPath("$.amount").value(75.00));

        assertThat(expenseRepository.findAllByUserOrderByDateDesc(user)).isEmpty();
    }
}
