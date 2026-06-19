package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class AiUsageApiIntegrationTest {

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtUtil jwtUtil;

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
                .email("usage-test@test.com")
                .password(passwordEncoder.encode("pass"))
                .role(Role.USER)
                .build());
        String token = jwtUtil.generate(user.getEmail());
        authHeader = "Bearer " + token;
    }

    @Test
    void getUsage_authenticated_returnsPlanAndLimits() throws Exception {
        mockMvc.perform(get("/ai/usage")
                        .header(HttpHeaders.AUTHORIZATION, authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").exists())
                .andExpect(jsonPath("$.used").isNumber())
                .andExpect(jsonPath("$.limit").isNumber())
                .andExpect(jsonPath("$.remaining").isNumber())
                .andExpect(jsonPath("$.visionUsed").isNumber())
                .andExpect(jsonPath("$.visionLimit").isNumber())
                .andExpect(jsonPath("$.resetsAt").exists());
    }

    @Test
    void getUsage_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/ai/usage"))
                .andExpect(status().isUnauthorized());
    }
}
