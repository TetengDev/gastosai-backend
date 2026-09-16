package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.AppEventRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
@TestPropertySource(properties = {"gastos.ratelimit.write-per-minute=3"})
class AuthenticatedWriteRateLimitIntegrationTest extends PostgresBackedTest {

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtUtil jwtUtil;
    @Autowired AppEventRepository appEventRepository;

    MockMvc mockMvc;
    String authHeader;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        appEventRepository.deleteAll();
        userRepository.deleteAll();
        User user = userRepository.save(User.builder()
                .name("Write User").email("write@test.com")
                .password(passwordEncoder.encode("pw")).build());
        authHeader = "Bearer " + jwtUtil.generate(user.getEmail());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder createExpense() {
        return post("/expenses")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":100,\"description\":\"test\"}");
    }

    /** An empty body leaves every setting alone, so the request is a write and nothing else. */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder updateAiSettings() {
        return put("/user/ai-settings")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
    }

    private void exhaustTheWriteBudget() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(createExpense());
        }
    }

    @Test
    void fourthWrite_returns429() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(createExpense());
        }
        mockMvc.perform(createExpense()).andExpect(status().isTooManyRequests());
    }

    @Test
    void rateLimitBreach_recordsAppEvent() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(createExpense());
        }
        assertThat(appEventRepository.findAll())
                .anyMatch(e -> "WRITE_RATE_LIMIT".equals(e.getEventType())
                        && "WARN".equals(e.getSeverity())
                        && e.getHttpStatus() == 429);
    }

    @Test
    void getRequests_notLimited() throws Exception {
        exhaustTheWriteBudget();
        mockMvc.perform(get("/expenses").header("Authorization", authHeader))
                .andExpect(status().isOk());
    }

    /**
     * {@code PUT /user/ai-settings} writes encrypted AI provider keys and the AI languages, and
     * until TEN-383 it was the one authenticated write path covered by neither rate-limit
     * interceptor. It shares the per-user write bucket, and it sheds with the same 429 the other
     * limited writes return.
     */
    @Test
    void aiSettingsWrite_isLimitedOnTheSameBudgetAndStatus() throws Exception {
        exhaustTheWriteBudget();
        mockMvc.perform(updateAiSettings()).andExpect(status().isTooManyRequests());
    }

    @Test
    void aiSettingsDelete_isLimited() throws Exception {
        exhaustTheWriteBudget();
        mockMvc.perform(delete("/user/ai-settings/openai").header("Authorization", authHeader))
                .andExpect(status().isTooManyRequests());
    }

    /** A settings screen reads this on load; the write limit must not make that read unusable. */
    @Test
    void aiSettingsGet_notLimited() throws Exception {
        exhaustTheWriteBudget();
        mockMvc.perform(get("/user/ai-settings").header("Authorization", authHeader))
                .andExpect(status().isOk());
    }

    /**
     * The registered pattern is {@code /user/ai-settings/**}, not {@code /user/**} — a neighbouring
     * write under {@code /user} keeps the coverage it had before TEN-383, which is none.
     */
    @Test
    void otherUserWrites_coverageUnchanged() throws Exception {
        exhaustTheWriteBudget();
        mockMvc.perform(put("/user/profile")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Write User\",\"email\":\"write@test.com\"}"))
                .andExpect(status().isOk());
    }
}
