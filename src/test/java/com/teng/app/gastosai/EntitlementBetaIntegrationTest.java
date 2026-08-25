package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.dto.AiQueryResponse;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.AiQuotaExceededException;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.AiQueryService;
import com.teng.app.gastosai.service.AiQuotaService;
import com.teng.app.gastosai.service.ChatActionService;
import com.teng.app.gastosai.service.EntitlementService;
import com.teng.app.gastosai.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * The other half of the tier matrix: with monetization enforcement off — the default, and today's
 * production posture — every feature is unlocked, so shipping the entitlement system changes nothing
 * a user can see. {@link EntitlementEnforcementIntegrationTest} asserts the same matrix with the
 * flag on.
 *
 * <p>Two things deliberately do <em>not</em> relax when the flag is off, and both are asserted here
 * because "the flag unlocks everything" is the kind of summary that quietly grows too broad:
 *
 * <ul>
 *   <li>the <b>AI monthly quota</b>, which is a spend control gated on managed-AI mode
 *       ({@code gastos.ai.allow-shared-key}) rather than on monetization — the shared key is real
 *       money whether or not anyone is being billed for it;
 *   <li>the <b>admin view-as</b> simulation, which short-circuits ahead of the flag so a preview
 *       shows what the tier will look like once enforcement is on, not what it looks like now.
 * </ul>
 *
 * <p>{@code allow-shared-key=true} is set here only so the quota is live enough to assert; the
 * monetization flag is left at its default, which is the subject of the class.
 */
@SpringBootTest
@TestPropertySource(properties = {"gastos.ai.allow-shared-key=true"})
class EntitlementBetaIntegrationTest extends PostgresBackedTest {

    private static final int FREE_CATEGORY_CAP = 5;
    private static final int FREE_AI_QUOTA = 30;

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtUtil jwtUtil;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AiQuotaService aiQuotaService;
    @Autowired EntitlementService entitlementService;

    // Spies, not mocks: stubbed by default so a request that passes the gate stops at the boundary
    // instead of reaching a provider, but the quota tests below put the *real* method back on the
    // request path with doCallRealMethod(). That is what makes them fail if the
    // assertWithinQuota call is ever dropped, rather than merely re-asserting it from a stub.
    @MockitoSpyBean AiQueryService aiQueryService;
    @MockitoSpyBean ChatActionService chatActionService;

    MockMvc mockMvc;

    User beta;
    User admin;
    String authHeader;
    String adminAuth;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(webApplicationContext).apply(springSecurity()).build();

        beta = userRepository.save(User.builder()
                .name("Beta User").email("beta@test.com")
                .password(passwordEncoder.encode("pw")).role(Role.USER).build());
        admin = userRepository.save(User.builder()
                .name("Beta Admin").email("beta-admin@test.com")
                .password(passwordEncoder.encode("pw")).role(Role.ADMIN).build());

        authHeader = "Bearer " + jwtUtil.generate(beta.getEmail());
        adminAuth = "Bearer " + jwtUtil.generate(admin.getEmail());

        doReturn(new AiQueryResponse("stubbed answer"))
                .when(aiQueryService).runNaturalLanguageQuery(any(), any(), any());
        doReturn(new ChatResponse("text", "stubbed reply", null))
                .when(chatActionService).dispatch(any(), any(), any(), any());
    }

    @Test
    void entitlements_unlockAllFeaturesWhenNotEnforced() throws Exception {
        mockMvc.perform(get("/user/entitlements").header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.features.length()").value(FeatureKey.values().length));
    }

    @Test
    void freeUser_reachesThePremiumAiEndpoint_whenNotEnforced() throws Exception {
        mockMvc.perform(post("/ai/query")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"total spent this month\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void freeUser_keepsPremiumPersonas_whenNotEnforced() {
        assertThat(entitlementService.resolveChatMode("professional", beta)).isEqualTo("professional");
        assertThat(entitlementService.resolveChatMode("genz", beta)).isEqualTo("genz");
    }

    @Test
    void freeUser_passesTheCategoryCap_whenNotEnforced() throws Exception {
        for (int i = 1; i <= FREE_CATEGORY_CAP + 1; i++) {
            mockMvc.perform(post("/categories")
                            .header("Authorization", authHeader)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Category " + i + "\"}"))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void aiQuota_stillApplies_whenNotEnforced() {
        // The monetization flag gates feature access, not AI spend: the shared key costs real money
        // during the beta too, so the FREE cap of 30 holds regardless.
        seedSuccessfulAiUsage(beta, FREE_AI_QUOTA);

        assertThatThrownBy(() -> aiQuotaService.assertWithinQuota(beta, AiFeature.CHAT_CRUD_ASSISTANT))
                .isInstanceOf(AiQuotaExceededException.class);
    }

    @Test
    void aiQuota_isReachedOverHttp_andSurfacesAs429() throws Exception {
        // The quota is worth nothing if the user is never told it is what stopped them. The real
        // ChatActionService is on the path here — assertWithinQuota is its first statement, so no
        // provider is reached — which is what closes the gap the TEN-153 audit raised: drop that
        // call and this test stops seeing a 429.
        seedSuccessfulAiUsage(beta, FREE_AI_QUOTA);
        doCallRealMethod().when(chatActionService).dispatch(any(), any(), any(), any());

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"how much did I spend\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.title").value("AI Quota Exceeded"))
                .andExpect(jsonPath("$.detail").value("You've reached your monthly AI limit."));
    }

    @Test
    void aiQuota_overHttpOnTheQueryEndpoint_surfacesAs429() throws Exception {
        // FREE clears the AI_ANALYTICS gate here because enforcement is off, so the quota — not the
        // 402 — is the first thing this request can hit.
        seedSuccessfulAiUsage(beta, FREE_AI_QUOTA);
        doCallRealMethod().when(aiQueryService).runNaturalLanguageQuery(any(), any(), any());

        mockMvc.perform(post("/ai/query")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"total spent this month\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.title").value("AI Quota Exceeded"));
    }

    @Test
    void providerFailure_stillDegradesTo200() throws Exception {
        // The breaker's actual job, unchanged: a provider fault is not the user's problem.
        doThrow(new IllegalStateException("provider down"))
                .when(chatActionService).dispatch(any(), any(), any(), any());

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"how much did I spend\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("The AI assistant is temporarily unavailable. Please try again shortly."));
    }

    @Test
    void adminViewAsFree_previewsEnforcement_evenWhenNotEnforced() throws Exception {
        // View-as short-circuits ahead of the flag, so an admin can see the locked tier before the
        // switch is flipped — that is the whole point of the toggle.
        mockMvc.perform(post("/ai/query")
                        .header("Authorization", adminAuth)
                        .header("X-View-As-Plan", "FREE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"total spent this month\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.feature").value("AI_ANALYTICS"));

        mockMvc.perform(get("/user/entitlements")
                        .header("Authorization", adminAuth)
                        .header("X-View-As-Plan", "FREE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.features", org.hamcrest.Matchers.containsInAnyOrder("EXPORT_CSV", "NL_CHATBOT")));
    }

    @Test
    void admin_isUnlocked_whenNotEnforced() throws Exception {
        mockMvc.perform(get("/user/entitlements").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.features.length()").value(FeatureKey.values().length));
    }

    private void seedSuccessfulAiUsage(User user, int count) {
        List<Object[]> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new Object[]{user.getId(), "openai", "gpt-4o-mini",
                    AiFeature.CHAT_CRUD_ASSISTANT.name(), "SUCCESS", LocalDateTime.now()});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO ai_usage (user_id, provider, model, feature, status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                rows);
    }
}
