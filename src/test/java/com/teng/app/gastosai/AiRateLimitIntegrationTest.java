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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
@TestPropertySource(properties = "gastos.ratelimit.ai-per-minute=2")
class AiRateLimitIntegrationTest {

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
                .name("Rate User").email("rate@test.com").password(passwordEncoder.encode("pw")).build());
        authHeader = "Bearer " + jwtUtil.generate(user.getEmail());
    }

    @Test
    void aiEndpoint_returns429_pastQuota() throws Exception {
        // Window allows 2; the first two pass the limiter (outcome irrelevant), the third is throttled.
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(query());
        }
        mockMvc.perform(query()).andExpect(status().isTooManyRequests());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder query() {
        return post("/ai/query")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"total spent this month\"}");
    }
}
