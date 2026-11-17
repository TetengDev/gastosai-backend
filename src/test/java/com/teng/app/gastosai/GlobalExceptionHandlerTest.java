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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.hibernate.exception.JDBCConnectionException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
    void unmatchedV2Route_returns404NotA500() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v2/user")
                        .header("Authorization", authHeader))
                .andExpect(status().isNotFound())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("No endpoint matches this request.");
        // The framework fills ProblemDetail's "instance" with the request path, which is the
        // client's own input; what must not escape is anything about the server internals.
        assertThat(body).doesNotContain("NoResourceFoundException").doesNotContain("com.teng")
                .doesNotContain("static resource");
    }

    @Test
    void unmatchedBareRoute_returns404NotA500() throws Exception {
        mockMvc.perform(get("/user").header("Authorization", authHeader))
                .andExpect(status().isNotFound());
    }

    @Test
    void wrongMethodOnExistingRoute_returns405WithAllowHeader() throws Exception {
        MvcResult result = mockMvc.perform(delete("/api/v2/user/profile")
                        .header("Authorization", authHeader))
                .andExpect(status().isMethodNotAllowed())
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.ALLOW)).contains("GET").contains("PUT");
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("This endpoint does not support the request method.");
        assertThat(body).doesNotContain("HttpRequestMethodNotSupportedException").doesNotContain("com.teng")
                .doesNotContain("not supported");
    }

    @Test
    void unsupportedContentType_returns415() throws Exception {
        MvcResult result = mockMvc.perform(put("/api/v2/user/profile")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("name=Someone"))
                .andExpect(status().isUnsupportedMediaType())
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.ACCEPT)).contains("application/json");
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("This endpoint does not accept the request's content type.");
        assertThat(body).doesNotContain("HttpMediaTypeNotSupportedException").doesNotContain("text/plain");
    }

    @Test
    void unsatisfiableAccept_returns406() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v2/features")
                        .header("Authorization", authHeader)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andReturn();

        // The client's Accept cannot be met, so the problem body must still go out as problem+json.
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(result.getResponse().getContentAsString())
                .contains("This endpoint cannot produce a response in an acceptable media type.")
                .doesNotContain("HttpMediaTypeNotAcceptableException").doesNotContain("com.teng");
    }

    @Test
    void unsupportedRequest_isNotRecordedAsAnError() {
        AppEventService appEventService = mock(AppEventService.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(appEventService);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v2/user/profile");
        when(request.getMethod()).thenReturn("DELETE");

        ResponseEntity<ProblemDetail> response = handler.unsupportedRequest(
                new HttpRequestMethodNotSupportedException("DELETE", List.of("GET", "PUT")), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).contains(HttpMethod.GET, HttpMethod.PUT);
        verifyNoInteractions(appEventService);
    }

    @Test
    void noHandlerFound_returns404WithoutRecordingAnError() {
        AppEventService appEventService = mock(AppEventService.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(appEventService);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/nope");
        when(request.getMethod()).thenReturn("GET");

        ResponseEntity<ProblemDetail> response = handler.noRoute(
                new NoHandlerFoundException("GET", "/nope", HttpHeaders.EMPTY), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getDetail()).isEqualTo("No endpoint matches this request.");
        verifyNoInteractions(appEventService);
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

    /**
     * What a request sees when Postgres cannot be reached (TEN-511). Driven through the real
     * exception resolver, not by calling a handler method, because the bug is which handler the
     * resolver picks: {@code CannotCreateTransactionException} is not a {@code DataAccessException}
     * and fell to the 500 catch-all, and the pool's own failures matched the 400 handler.
     */
    static Stream<RuntimeException> unreachableDatabaseFailures() {
        SQLTransientConnectionException poolTimeout = new SQLTransientConnectionException(
                "HikariPool-1 - Connection is not available, request timed out after 10000ms.");
        return Stream.of(
                new DataAccessResourceFailureException("Unable to acquire JDBC Connection", poolTimeout),
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
                        new JDBCConnectionException("Unable to acquire JDBC Connection", poolTimeout)),
                new JDBCConnectionException("An I/O error occurred while sending to the backend",
                        new SQLException("Read timed out", "08006")),
                // JdbcTemplate's SQLExceptionSubclassTranslator turns the pool's exception into this
                // TransientDataAccessException, which is not a DataAccessResourceFailureException.
                new TransientDataAccessResourceException("Connection is not available", poolTimeout),
                // A socket timeout inside a @Transactional service: the rollback then fails on the
                // connection Hikari has already closed, and Spring throws that in place of the
                // original failure. Hikari's closed proxy reports no SQLState. Observed at runtime.
                new JpaSystemException(new org.hibernate.TransactionException(
                        "Unable to rollback against JDBC Connection", new SQLException("Connection is closed"))));
    }

    static Stream<RuntimeException> otherDataAccessFailures() {
        return Stream.of(
                new DataIntegrityViolationException("duplicate key value violates unique constraint"),
                // A commit refused by the database itself, not a lost connection.
                new JpaSystemException(new org.hibernate.TransactionException(
                        "Unable to commit against JDBC Connection",
                        new SQLException("duplicate key value violates unique constraint", "23505"))));
    }

    @ParameterizedTest
    @MethodSource("unreachableDatabaseFailures")
    void unreachableDatabase_returns503ProblemDetailWithoutInternals(RuntimeException failure) throws Exception {
        AppEventService appEventService = mock(AppEventService.class);
        MockMvc standalone = MockMvcBuilders.standaloneSetup(new ThrowingEndpoint(failure))
                .setControllerAdvice(new GlobalExceptionHandler(appEventService))
                .build();

        MvcResult result = standalone.perform(get("/throws"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.title").value("Service Unavailable"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("Exception").doesNotContain("Hikari").doesNotContain("\tat ")
                .doesNotContain("JDBC");
        // Recording to app_event would write to the database that is down.
        verifyNoInteractions(appEventService);
    }

    @ParameterizedTest
    @MethodSource("otherDataAccessFailures")
    void otherDataAccessFailure_stillReturns400(RuntimeException failure) throws Exception {
        MockMvc standalone = MockMvcBuilders.standaloneSetup(new ThrowingEndpoint(failure))
                .setControllerAdvice(new GlobalExceptionHandler(mock(AppEventService.class)))
                .build();

        standalone.perform(get("/throws"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Query execution failed"));
    }

    /**
     * An inner (non-static) class on purpose: standalone MockMvc only maps a {@code @Controller},
     * and component scanning skips classes that are not independent, so this stays out of every
     * application context and out of {@code openapi.json}.
     */
    @RestController
    class ThrowingEndpoint {
        private final RuntimeException failure;

        ThrowingEndpoint(RuntimeException failure) {
            this.failure = failure;
        }

        @GetMapping("/throws")
        String fail() {
            throw failure;
        }
    }
}
