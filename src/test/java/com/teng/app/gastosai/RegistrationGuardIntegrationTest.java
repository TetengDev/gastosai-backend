package com.teng.app.gastosai;

import com.teng.app.gastosai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@TestPropertySource(properties = {
        "gastos.security.register-ip-daily-max=2",
        "gastos.security.register-daily-max=3"
})
class RegistrationGuardIntegrationTest {

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        userRepository.deleteAll();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder registerAs(
            String name, String email, String ip) {
        return post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", ip)
                .content("{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"password123\"}");
    }

    @Test
    void ipCap_thirdRegistrationFromSameIp_returns429() throws Exception {
        mockMvc.perform(registerAs("A", "a@test.com", "10.0.0.1")).andExpect(status().isCreated());
        mockMvc.perform(registerAs("B", "b@test.com", "10.0.0.1")).andExpect(status().isCreated());
        mockMvc.perform(registerAs("C", "c@test.com", "10.0.0.1")).andExpect(status().isTooManyRequests());
    }

    @Test
    void ipCap_differentIp_isIndependent() throws Exception {
        mockMvc.perform(registerAs("A", "a2@test.com", "10.0.0.2")).andExpect(status().isCreated());
        mockMvc.perform(registerAs("B", "b2@test.com", "10.0.0.2")).andExpect(status().isCreated());
        mockMvc.perform(registerAs("C", "c2@test.com", "10.0.0.3")).andExpect(status().isCreated());
    }

    @Test
    void globalCap_fourthRegistrationFromAnyIp_returns429() throws Exception {
        mockMvc.perform(registerAs("A", "ga@test.com", "1.1.1.1")).andExpect(status().isCreated());
        mockMvc.perform(registerAs("B", "gb@test.com", "2.2.2.2")).andExpect(status().isCreated());
        mockMvc.perform(registerAs("C", "gc@test.com", "3.3.3.3")).andExpect(status().isCreated());
        mockMvc.perform(registerAs("D", "gd@test.com", "4.4.4.4")).andExpect(status().isTooManyRequests());
    }
}
