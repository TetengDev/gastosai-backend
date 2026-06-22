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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
class BudgetRuleApiIntegrationTest {

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtUtil jwtUtil;

    MockMvc mockMvc;
    String auth;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        userRepository.deleteAll();
        User u = userRepository.save(User.builder()
                .name("Rule User").email("rule@test.com").password(passwordEncoder.encode("pw")).build());
        auth = "Bearer " + jwtUtil.generate(u.getEmail());
    }

    @Test
    void upsertCustomRule_thenGet_returnsStoredSplit() throws Exception {
        mockMvc.perform(put("/budget-rules").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleType\":\"CUSTOM\",\"monthlyIncome\":10000,\"needsPct\":60,\"wantsPct\":30,\"savingsPct\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsPct").value(60));

        mockMvc.perform(get("/budget-rules").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleType").value("CUSTOM"))
                .andExpect(jsonPath("$.savingsPct").value(10));
    }

    @Test
    void customRule_withBadPercentages_returns400() throws Exception {
        mockMvc.perform(put("/budget-rules").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleType\":\"CUSTOM\",\"monthlyIncome\":10000,\"needsPct\":50,\"wantsPct\":30,\"savingsPct\":30}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void summary_computesTargetsFromIncome() throws Exception {
        mockMvc.perform(put("/budget-rules").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleType\":\"FIFTY_THIRTY_TWENTY\",\"monthlyIncome\":10000}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/budget-rules/summary?month=2026-06").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets.length()").value(3))
                .andExpect(jsonPath("$.buckets[0].target").value(5000.00));
    }

    @Test
    void summary_malformedMonth_returns400() throws Exception {
        mockMvc.perform(get("/budget-rules/summary?month=NOPE").header("Authorization", auth))
                .andExpect(status().isBadRequest());
    }

    @Test
    void newUser_getsDefaultRule_disabled() throws Exception {
        mockMvc.perform(get("/budget-rules").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.ruleType").value("FIFTY_THIRTY_TWENTY"))
                .andExpect(jsonPath("$.monthlyIncome").value(0));
    }

    @Test
    void enableEndpoint_turnsFeatureOn() throws Exception {
        mockMvc.perform(put("/budget-rules/enabled").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(get("/budget-rules").header("Authorization", auth))
                .andExpect(jsonPath("$.enabled").value(true));
    }
}
