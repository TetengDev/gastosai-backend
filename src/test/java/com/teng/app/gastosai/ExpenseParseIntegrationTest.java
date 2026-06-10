package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.ExpenseParser;
import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.dto.ParsedExpenseResult;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class ExpenseParseIntegrationTest {

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtUtil jwtUtil;

    @MockitoBean
    ExpenseParser expenseParser;

    MockMvc mockMvc;
    String authHeader;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        userRepository.deleteAll();
        User user = userRepository.save(User.builder()
                .name("Test User")
                .email("parse-test@test.com")
                .password(passwordEncoder.encode("password"))
                .build());
        authHeader = "Bearer " + jwtUtil.generate(user.getEmail());
    }

    @Test
    void parseReturnsSaveableDraftWhenHighConfidence() throws Exception {
        ParsedExpenseResult draft = new ParsedExpenseResult(
                new BigDecimal("250.00"),
                "Food",
                LocalDateTime.of(2026, 6, 10, 12, 0),
                "Lunch at Jollibee",
                "HIGH",
                true,
                null
        );
        when(expenseParser.parse("spent 250 on lunch at Jollibee")).thenReturn(draft);

        mockMvc.perform(post("/expenses/parse")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "spent 250 on lunch at Jollibee"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(250.00))
                .andExpect(jsonPath("$.category").value("Food"))
                .andExpect(jsonPath("$.description").value("Lunch at Jollibee"))
                .andExpect(jsonPath("$.confidence").value("HIGH"))
                .andExpect(jsonPath("$.saveable").value(true))
                .andExpect(jsonPath("$.hint").doesNotExist());
    }

    @Test
    void parseReturnsNotSaveableWhenLowConfidence() throws Exception {
        ParsedExpenseResult vague = new ParsedExpenseResult(
                BigDecimal.ZERO,
                "Uncategorized",
                LocalDateTime.of(2026, 6, 10, 0, 0),
                "Payment, details not specified",
                "LOW",
                false,
                "Amount or description unclear - please provide more details."
        );
        when(expenseParser.parse("may binayad ako kanina")).thenReturn(vague);

        mockMvc.perform(post("/expenses/parse")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "may binayad ako kanina"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saveable").value(false))
                .andExpect(jsonPath("$.hint").value("Amount or description unclear - please provide more details."));
    }

    @Test
    void parseRejectsBlankText() throws Exception {
        mockMvc.perform(post("/expenses/parse")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": ""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void parseRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/expenses/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "coffee 75 pesos"}
                                """))
                .andExpect(status().isForbidden());
    }
}
