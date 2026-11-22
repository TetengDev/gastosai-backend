package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.support.PostgresBackedTest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
class BudgetRuleApiIntegrationTest extends PostgresBackedTest {

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtUtil jwtUtil;

    MockMvc mockMvc;
    String auth;
    User user;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
        user = userRepository.save(User.builder()
                .name("Rule User").email("rule@test.com").password(passwordEncoder.encode("pw")).build());
        auth = "Bearer " + jwtUtil.generate(user.getEmail());
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
                .andExpect(jsonPath("$.buckets[0].target").value(5000.00))
                .andExpect(jsonPath("$.buckets[0].status").value("ON_TRACK"));
    }

    @Test
    void v2Summary_bucketCarriesTheCategoryStatus() throws Exception {
        mockMvc.perform(put("/api/v2/budget-rules").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleType\":\"FIFTY_THIRTY_TWENTY\",\"monthlyIncome\":1000000}"))
                .andExpect(status().isOk());
        Category rent = categoryRepository.save(Category.builder().name("Rent").user(user).build());
        mockMvc.perform(put("/api/v2/budget-rules/buckets").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignments\":[{\"categoryId\":%d,\"bucket\":\"NEEDS\"}]}".formatted(rent.getId())))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/expenses").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":4000.00,\"category\":\"Rent\",\"date\":\"2026-06-05T12:00:00\",\"description\":\"Rent\"}"))
                .andExpect(status().isCreated());

        // NEEDS: 4000 of a 5000 target is exactly 80% - the WARNING boundary, as for a category.
        mockMvc.perform(get("/api/v2/budget-rules/summary?month=2026-06").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets[0].bucket").value("NEEDS"))
                .andExpect(jsonPath("$.buckets[0].percentUsed").value(80.0))
                .andExpect(jsonPath("$.buckets[0].status").value("WARNING"))
                .andExpect(jsonPath("$.buckets[1].status").value("ON_TRACK"))
                .andExpect(jsonPath("$.buckets[2].status").value("ON_TRACK"));
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
