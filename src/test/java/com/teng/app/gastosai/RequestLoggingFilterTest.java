package com.teng.app.gastosai;

import com.teng.app.gastosai.config.RequestLoggingFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLoggingFilterTest {

	private final RequestLoggingFilter filter = new RequestLoggingFilter();

	@Test
	void generatesRequestIdAndEchoesItInResponseHeader() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/expenses");
		MockHttpServletResponse res = new MockHttpServletResponse();

		filter.doFilter(req, res, new MockFilterChain());

		assertThat(res.getHeader("X-Request-Id")).isNotBlank();
	}

	@Test
	void respectsIncomingRequestIdHeader() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/expenses");
		req.addHeader("X-Request-Id", "abc-123");
		MockHttpServletResponse res = new MockHttpServletResponse();

		filter.doFilter(req, res, new MockFilterChain());

		assertThat(res.getHeader("X-Request-Id")).isEqualTo("abc-123");
	}

	@Test
	void putsRequestIdInMdcDuringChainAndClearsAfter() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/expenses");
		MockHttpServletResponse res = new MockHttpServletResponse();
		AtomicReference<String> idDuringChain = new AtomicReference<>();
		FilterChain chain = (request, response) -> idDuringChain.set(MDC.get("requestId"));

		filter.doFilter(req, res, chain);

		assertThat(idDuringChain.get()).isNotBlank();
		for (String key : new String[] {"requestId", "method", "path", "status", "durationMs", "userId"}) {
			assertThat(MDC.get(key)).as("MDC key %s cleared", key).isNull();
		}
	}

	@Test
	void skipsActuatorPaths() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
		MockHttpServletResponse res = new MockHttpServletResponse();

		filter.doFilter(req, res, new MockFilterChain());

		assertThat(res.getHeader("X-Request-Id")).isNull();
	}
}
