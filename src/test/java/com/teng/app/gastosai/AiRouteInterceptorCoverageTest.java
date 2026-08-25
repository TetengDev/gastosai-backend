package com.teng.app.gastosai;

import com.teng.app.gastosai.support.PostgresBackedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every route that reaches the model must carry the two gates that make that affordable.
 *
 * <p>The gates are registered in {@code WebConfig} by <em>path</em>, so a new endpoint joins
 * neither list by default. Nothing fails when one is missed: the endpoint works, the tests pass,
 * and the missing line sits in a file the endpoint's own change never touches. TEN-176 added
 * {@code /expenses/quick-add}, which parses free text through the model, and it was registered on
 * neither — so a bring-your-own-key user's parse would have been billed to the platform key, and
 * the AI rate limit would not have applied at all.
 *
 * <p>This test is the failing signal that was missing. It asks the real handler mapping which
 * interceptors each route actually resolves to, rather than reading the configuration back.
 */
@SpringBootTest
class AiRouteInterceptorCoverageTest extends PostgresBackedTest {

    /** Routes that call the model, and therefore need the key context and the AI rate limit. */
    private static final List<String> MODEL_BACKED_ROUTES = List.of(
            "/ai/chat",
            "/ai/query",
            "/expenses/parse",
            "/expenses/quick-add");

    // Actuator contributes a second RequestMappingHandlerMapping, so name the MVC one.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyModelBackedRouteCarriesTheKeyContextAndRateLimitGates() throws Exception {
        Set<String> ungated = new TreeSet<>();
        for (String route : MODEL_BACKED_ROUTES) {
            Set<String> names = interceptorNamesFor(route);
            if (!names.contains("AiKeyContextInterceptor") || !names.contains("AiRateLimitInterceptor")) {
                ungated.add(route + " -> " + names);
            }
        }
        assertThat(ungated)
                .as("a model-backed route with no key context or no AI rate limit: the platform pays "
                        + "for a BYO user's parse, and the call is unmetered")
                .isEmpty();
    }

    private Set<String> interceptorNamesFor(String route) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", route);
        HandlerExecutionChain chain = handlerMapping.getHandler(request);
        assertThat(chain).as("no handler mapped for %s — has the route moved?", route).isNotNull();
        assertThat(chain.getHandler()).isInstanceOf(HandlerMethod.class);
        Set<String> names = new TreeSet<>();
        for (var interceptor : chain.getInterceptorList()) {
            names.add(interceptor.getClass().getSimpleName());
        }
        return names;
    }
}
