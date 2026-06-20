package com.teng.app.gastosai;

import com.teng.app.gastosai.repository.MagicLinkTokenRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class MagicLinkApiIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        CapturingEmailSender capturingEmailSender() {
            return new CapturingEmailSender();
        }
    }

    static class CapturingEmailSender implements EmailSender {
        final List<String> links = new ArrayList<>();

        @Override
        public void sendMagicLink(String toEmail, String link) {
            links.add(link);
        }

        String lastLink() {
            return links.get(links.size() - 1);
        }

        void clear() {
            links.clear();
        }
    }

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired MagicLinkTokenRepository tokenRepository;
    @Autowired CapturingEmailSender capturingEmailSender;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        capturingEmailSender.clear();
    }

    private String extractToken(String link) {
        return link.substring(link.indexOf("token=") + 6);
    }

    @Test
    void requestMagicLink_unknownEmail_returns200AndCreatesUserAndToken() throws Exception {
        mockMvc.perform(post("/auth/magic-link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true));

        assertThat(userRepository.findByEmail("unknown@example.com")).isPresent();
        assertThat(tokenRepository.count()).isEqualTo(1);
    }

    @Test
    void requestMagicLink_knownEmail_returns200SameShape() throws Exception {
        mockMvc.perform(post("/auth/magic-link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"known@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true));

        mockMvc.perform(post("/auth/magic-link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"known@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true));

        assertThat(userRepository.findByEmail("known@example.com")).isPresent();
    }

    @Test
    void requestMagicLink_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/auth/magic-link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void endToEnd_requestThenVerify_returnsJwt() throws Exception {
        mockMvc.perform(post("/auth/magic-link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"e2e@example.com\"}"))
                .andExpect(status().isOk());

        var rawToken = extractToken(capturingEmailSender.lastLink());

        mockMvc.perform(post("/auth/magic-link/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.email").value("e2e@example.com"));
    }

    @Test
    void verifyMagicLink_badToken_returns401() throws Exception {
        mockMvc.perform(post("/auth/magic-link/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"totallywrongtoken\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verifyMagicLink_usedToken_returns401() throws Exception {
        mockMvc.perform(post("/auth/magic-link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"reuse@example.com\"}"))
                .andExpect(status().isOk());

        var rawToken = extractToken(capturingEmailSender.lastLink());

        mockMvc.perform(post("/auth/magic-link/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/magic-link/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }
}
