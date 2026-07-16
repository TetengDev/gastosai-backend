package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.AppEventRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
@TestPropertySource(properties = {"gastos.ratelimit.write-per-minute=3"})
class AuthenticatedWriteRateLimitIntegrationTest {

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
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(createExpense());
        }
        mockMvc.perform(get("/expenses").header("Authorization", authHeader))
                .andExpect(status().isOk());
    }
}
