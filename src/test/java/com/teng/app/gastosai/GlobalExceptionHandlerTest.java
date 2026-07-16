package com.teng.app.gastosai;

import com.teng.app.gastosai.exception.GlobalExceptionHandler;
import com.teng.app.gastosai.service.AppEventService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

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
