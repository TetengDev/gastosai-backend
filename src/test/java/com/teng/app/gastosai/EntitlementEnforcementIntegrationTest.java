package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
@TestPropertySource(properties = {"gastos.monetization.enforce=true", "gastos.ai.allow-shared-key=true"})
class EntitlementEnforcementIntegrationTest {

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtUtil jwtUtil;

    MockMvc mockMvc;
    String authHeader;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        userRepository.deleteAll();
        User user = userRepository.save(User.builder()
                .name("Free User").email("free@test.com").password(passwordEncoder.encode("pw")).build());
        authHeader = "Bearer " + jwtUtil.generate(user.getEmail());
    }

    @Test
    void freeUser_blockedFromPremiumAiEndpoint_with402() throws Exception {
        mockMvc.perform(post("/ai/query")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"total spent this month\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.feature").value("AI_ANALYTICS"));
    }

    @Test
    void entitlements_reportFreePlanFeatures_whenEnforced() throws Exception {
        mockMvc.perform(get("/user/entitlements").header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.features").isArray())
                .andExpect(jsonPath("$.features", org.hamcrest.Matchers.containsInAnyOrder("EXPORT_CSV", "NL_CHATBOT")));
    }
}
