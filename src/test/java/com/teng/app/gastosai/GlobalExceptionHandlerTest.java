package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.GlobalExceptionHandler;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.AppEventService;
import com.teng.app.gastosai.support.PostgresBackedTest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The malformed-body cases go through the real HTTP stack on purpose: the bug this covers was that
 * the advice's catch-all outranked Spring's default resolver, and only a request that travels the
 * whole dispatcher chain can tell a 400 handler from a 500 one.
 */
@SpringBootTest
class GlobalExceptionHandlerTest extends PostgresBackedTest {

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtUtil jwtUtil;

    MockMvc mockMvc;
    String authHeader;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        User user = userRepository.save(User.builder()
                .name("Malformed Body User")
                .email("malformed-body@test.com")
                .password(passwordEncoder.encode("password"))
                .build());

        authHeader = "Bearer " + jwtUtil.generate(user.getEmail());
    }

    @Test
    void unknownEnumValue_returns400NamingTheFieldAndItsAllowedValues() throws Exception {
        MvcResult result = mockMvc.perform(post("/recurring")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Electric Bill","amount":1500.00,"frequency":"HOURLY"}
                                """))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("frequency").contains("MONTHLY, WEEKLY, YEARLY");
        // Jackson's own message quotes the rejected value and the target class; neither may escape.
        assertThat(body).doesNotContain("HOURLY").doesNotContain("com.teng").doesNotContain("deserialize");
    }

    @Test
    void unparseableBody_returns400WithoutNamingAField() throws Exception {
        MvcResult result = mockMvc.perform(post("/recurring")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("Request body could not be read.");
        assertThat(body).doesNotContain("com.teng").doesNotContain("JsonParseException");
    }

    @Test
    void wrongTypeForField_returns400NamingTheField() throws Exception {
        MvcResult result = mockMvc.perform(post("/recurring")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Electric Bill","amount":"a lot","frequency":"MONTHLY"}
                                """))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("amount");
        assertThat(body).doesNotContain("a lot").doesNotContain("com.teng");
    }

    @Test
    void unhandled_recordsErrorAndReturnsGeneric500() {
        AppEventService appEventService = mock(AppEventService.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(appEventService);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/expenses");
        when(request.getMethod()).thenReturn("POST");

        ResponseEntity<ProblemDetail> response =
                handler.unhandled(new IllegalStateException("secret internal detail"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getDetail()).isEqualTo("An unexpected error occurred.");
        verify(appEventService).recordError(eq("/expenses"), eq(500), eq("secret internal detail"),
                org.mockito.ArgumentMatchers.contains("IllegalStateException"));
    }
}
