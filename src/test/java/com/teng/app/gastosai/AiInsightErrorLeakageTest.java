package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.AiInsightService;
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
class AiInsightErrorLeakageTest {

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtUtil jwtUtil;

    @MockitoBean
    AiInsightService aiInsightService;

    MockMvc mockMvc;
    String authHeader;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        userRepository.deleteAll();
        User user = userRepository.save(User.builder()
                .name("Leakage Test User")
                .email("leakage@test.com")
                .password(passwordEncoder.encode("pw"))
                .build());
        authHeader = "Bearer " + jwtUtil.generate(user.getEmail());
    }

    @Test
    void internalException_doesNotLeakMessage_returns500WithGenericMessage() throws Exception {
        when(aiInsightService.getTopCategory(any(), eq("2026-06")))
                .thenThrow(new RuntimeException("Internal db connection error: password=hunter2"));

        mockMvc.perform(get("/ai/insights/top-category")
                        .param("month", "2026-06")
                        .header("Authorization", authHeader))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Failed to generate insight. Please try again later."));
    }
}
