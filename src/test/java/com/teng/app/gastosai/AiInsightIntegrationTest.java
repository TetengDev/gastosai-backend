package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.LlmResult;
import com.teng.app.gastosai.ai.LlmUsage;
import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@TestPropertySource(properties = "gastos.ai.allow-shared-key=true")
class AiInsightIntegrationTest {

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtUtil jwtUtil;

    @MockitoBean
    SqlGenerator sqlGenerator;

    MockMvc mockMvc;
    String authHeader;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        userRepository.deleteAll();
        User user = userRepository.save(User.builder()
                .name("Insight Test User")
                .email("insight-test@test.com")
                .password(passwordEncoder.encode("password"))
                .build());
        authHeader = "Bearer " + jwtUtil.generate(user.getEmail());
    }

    @Test
    void topCategory_returns200() throws Exception {
        mockMvc.perform(get("/ai/insights/top-category")
                        .param("month", "2026-06")
                        .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-06"))
                .andExpect(jsonPath("$.category").exists());
    }

    @Test
    void monthSummary_returns200() throws Exception {
        when(sqlGenerator.generateInsightSummary(any(), eq("month-summary"), eq("plain")))
                .thenReturn(LlmResult.of("You had a quiet month spending-wise.", LlmUsage.absent()));

        mockMvc.perform(get("/ai/insights/month-summary")
                        .param("month", "2026-06")
                        .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-06"))
                .andExpect(jsonPath("$.summary").value("You had a quiet month spending-wise."));
    }

    @Test
    void recommendations_returns200() throws Exception {
        when(sqlGenerator.generateInsightSummary(any(), eq("recommendations"), eq("plain")))
                .thenReturn(LlmResult.of("[\"Reduce Food spending.\",\"Track Transport costs.\"]", LlmUsage.absent()));

        mockMvc.perform(get("/ai/insights/recommendations")
                        .param("month", "2026-06")
                        .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-06"))
                .andExpect(jsonPath("$.recommendations").isArray());
    }

    @Test
    void invalidMonth_returns400() throws Exception {
        mockMvc.perform(get("/ai/insights/top-category")
                        .param("month", "June-2026")
                        .header("Authorization", authHeader))
                .andExpect(status().isBadRequest());
    }
}
