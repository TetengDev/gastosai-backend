package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.dto.AiQueryResponse;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.SubscriptionPlan;
import com.teng.app.gastosai.entity.SubscriptionStatus;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.entity.UserSubscription;
import com.teng.app.gastosai.exception.AiQuotaExceededException;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.SubscriptionPlanRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.repository.UserSubscriptionRepository;
import com.teng.app.gastosai.service.AiQueryService;
import com.teng.app.gastosai.service.AiQuotaService;
import com.teng.app.gastosai.service.CategorySeedService;
import com.teng.app.gastosai.service.ChatActionService;
import com.teng.app.gastosai.service.EntitlementService;
import com.teng.app.gastosai.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * The tier access matrix, asserted end to end with {@code gastos.monetization.enforce=true} — the
 * flag production will eventually be flipped to. {@link EntitlementBetaIntegrationTest} is the
 * mirror of this class with the flag off, and the two together are the whole matrix: what the
 * switch buys, and what it costs nobody while it stays off.
 *
 * <p>Enforcement has three independent gates, and each is exercised against a real database here
 * rather than a stubbed {@code EntitlementService}, because all three read seeded reference data:
 *
 * <ul>
 *   <li><b>Feature grants</b> — {@code plan_features} rows, via the {@code @RequiresFeature}
 *       interceptor, surfacing as {@code 402 Payment Required};
 *   <li><b>Numeric caps</b> — the monthly AI quota and the per-plan category limit, which are
 *       config, not rows, and so are asserted at their exact boundary rather than approximately;
 *   <li><b>Graceful degradation</b> — chat personas, which fall back to {@code plain} instead of
 *       failing, so a downgraded user still gets a reply.
 * </ul>
 *
 * <p>{@code AiQueryService} and {@code ChatActionService} are mocked: this class asserts who is let
 * through the gate, and letting a real call proceed past it would reach a live provider. The quota
 * arithmetic behind the gate is asserted directly against {@link AiQuotaService}, whose inputs are
 * {@code ai_usage} rows this class seeds.
 */
@SpringBootTest
@TestPropertySource(properties = {"gastos.monetization.enforce=true", "gastos.ai.allow-shared-key=true"})
class EntitlementEnforcementIntegrationTest extends PostgresBackedTest {

    /** The FREE category cap from {@code CategoryLimitProperties}; the 6th create is the one that fails. */
    private static final int FREE_CATEGORY_CAP = 5;

    /** The starter set {@code CategorySeedService} writes at registration. System-provided: uncapped. */
    private static final int SEEDED_CATEGORY_COUNT = 13;

    private static final int FREE_AI_QUOTA = 30;
    private static final int PREMIUM_AI_QUOTA = 300;
    private static final int TRIAL_AI_QUOTA = 50;

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired SubscriptionPlanRepository planRepository;
    @Autowired UserSubscriptionRepository userSubscriptionRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtUtil jwtUtil;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AiQuotaService aiQuotaService;
    @Autowired EntitlementService entitlementService;
    @Autowired CategorySeedService categorySeedService;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ExpenseRepository expenseRepository;

    // Mocked so a request that passes the gate stops at the boundary instead of calling a provider.
    @MockitoBean AiQueryService aiQueryService;
    @MockitoBean ChatActionService chatActionService;

    MockMvc mockMvc;

    User free;
    User premium;
    User trial;
    User admin;

    String freeAuth;
    String premiumAuth;
    String trialAuth;
    String adminAuth;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(webApplicationContext).apply(springSecurity()).build();

        free = createUser("Free User", "free@test.com", Role.USER);
        premium = createUser("Premium User", "premium@test.com", Role.USER);
        trial = createUser("Trial User", "trial@test.com", Role.USER);
        admin = createUser("Admin User", "admin-enforce@test.com", Role.ADMIN);

        // FREE is the implicit default and needs no subscription row — that is the point of it.
        subscribe(premium, PlanKey.PREMIUM, SubscriptionStatus.ACTIVE);
        subscribe(trial, PlanKey.TRIAL, SubscriptionStatus.TRIAL);

        freeAuth = bearer(free);
        premiumAuth = bearer(premium);
        trialAuth = bearer(trial);
        adminAuth = bearer(admin);

        when(aiQueryService.runNaturalLanguageQuery(any(), any(), any()))
                .thenReturn(new AiQueryResponse("stubbed answer"));
        when(chatActionService.dispatch(any(), any(), any(), any()))
                .thenReturn(new ChatResponse("text", "stubbed reply", null));
    }

    // ---------------------------------------------------------------- feature grants

    @Test
    void freeUser_blockedFromPremiumAiEndpoint_with402() throws Exception {
        mockMvc.perform(post("/ai/query")
                        .header("Authorization", freeAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"total spent this month\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.feature").value("AI_ANALYTICS"));
    }

    @Test
    void entitlements_reportFreePlanFeatures_whenEnforced() throws Exception {
        mockMvc.perform(get("/user/entitlements").header("Authorization", freeAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.features").isArray())
                .andExpect(jsonPath("$.features", org.hamcrest.Matchers.containsInAnyOrder("EXPORT_CSV", "NL_CHATBOT")));
    }

    @Test
    void premiumUser_reachesPremiumAiEndpoint_andHoldsEveryFeature() throws Exception {
        mockMvc.perform(post("/ai/query")
                        .header("Authorization", premiumAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"total spent this month\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/user/entitlements").header("Authorization", premiumAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("PREMIUM"))
                .andExpect(jsonPath("$.features.length()").value(FeatureKey.values().length));

        assertThat(entitlementService.describe(premium).features())
                .isEqualTo(EnumSet.allOf(FeatureKey.class));
    }

    @Test
    void trialUser_reachesPremiumAiEndpoint_andHoldsEveryFeature() throws Exception {
        mockMvc.perform(post("/ai/query")
                        .header("Authorization", trialAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"total spent this month\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/user/entitlements").header("Authorization", trialAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("TRIAL"))
                .andExpect(jsonPath("$.features.length()").value(FeatureKey.values().length));

        assertThat(entitlementService.describe(trial).features())
                .isEqualTo(EnumSet.allOf(FeatureKey.class));
    }

    // ---------------------------------------------------------------- AI quota

    @Test
    void free_aiRequest31_isBlocked_and30IsNot() {
        seedSuccessfulAiUsage(free, FREE_AI_QUOTA - 1);
        assertThatCode(() -> aiQuotaService.assertWithinQuota(free, AiFeature.CHAT_CRUD_ASSISTANT))
                .as("the 30th request, with 29 already spent")
                .doesNotThrowAnyException();

        seedSuccessfulAiUsage(free, 1);
        assertThatThrownBy(() -> aiQuotaService.assertWithinQuota(free, AiFeature.CHAT_CRUD_ASSISTANT))
                .as("the 31st request, with the full quota of 30 spent")
                .isInstanceOf(AiQuotaExceededException.class);

        assertThat(aiQuotaService.monthlyCap(PlanKey.FREE)).isEqualTo(FREE_AI_QUOTA);
    }

    @Test
    void premium_isUnlockedTo300Requests() {
        assertThat(aiQuotaService.monthlyCap(PlanKey.PREMIUM)).isEqualTo(PREMIUM_AI_QUOTA);

        // Past FREE's cap and still serving — the tier, not the endpoint, is what moved.
        seedSuccessfulAiUsage(premium, PREMIUM_AI_QUOTA - 1);
        assertThatCode(() -> aiQuotaService.assertWithinQuota(premium, AiFeature.CHAT_CRUD_ASSISTANT))
                .as("the 300th request, with 299 already spent")
                .doesNotThrowAnyException();

        seedSuccessfulAiUsage(premium, 1);
        assertThatThrownBy(() -> aiQuotaService.assertWithinQuota(premium, AiFeature.CHAT_CRUD_ASSISTANT))
                .as("the 301st request, with the full quota of 300 spent")
                .isInstanceOf(AiQuotaExceededException.class);
    }

    @Test
    void trial_isUnlockedTo50Requests() {
        assertThat(aiQuotaService.monthlyCap(PlanKey.TRIAL)).isEqualTo(TRIAL_AI_QUOTA);

        seedSuccessfulAiUsage(trial, TRIAL_AI_QUOTA - 1);
        assertThatCode(() -> aiQuotaService.assertWithinQuota(trial, AiFeature.CHAT_CRUD_ASSISTANT))
                .as("the 50th request, with 49 already spent")
                .doesNotThrowAnyException();

        seedSuccessfulAiUsage(trial, 1);
        assertThatThrownBy(() -> aiQuotaService.assertWithinQuota(trial, AiFeature.CHAT_CRUD_ASSISTANT))
                .as("the 51st request, with the full quota of 50 spent")
                .isInstanceOf(AiQuotaExceededException.class);
    }

    // ---------------------------------------------------------------- category cap

    @Test
    void free_sixthCategory_isBlockedWith402() throws Exception {
        // A user starting from zero categories, which is the cap's own arithmetic. It is not the
        // state a registered user is in — see free_afterRegistrationSeeding_cannotCreateAnyCategory.
        for (int i = 1; i <= FREE_CATEGORY_CAP; i++) {
            createCategory(freeAuth, "Category " + i).andExpect(status().isCreated());
        }

        createCategory(freeAuth, "Category " + (FREE_CATEGORY_CAP + 1))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.feature").value("CUSTOM_CATEGORIES"));
    }

    /**
     * The seed-versus-cap arithmetic, which this test has pinned since TEN-153 and which TEN-327
     * closed. It used to assert the break; it now asserts the rule that replaced it.
     *
     * <p>Every registration path — password, magic link and Google — calls
     * {@code CategorySeedService.seedPredefinedForUser}, which writes 13 starter categories. The
     * FREE cap is 5, and seeding runs <em>before</em> {@code SubscriptionService.startTrial}, so
     * the account is FREE while it happens. Before TEN-319 the seeder skipped the cap and the user
     * merely woke up over their limit, unable to create a fourteenth while looking at thirteen;
     * TEN-319 made every creation path enforce, which promoted that from a wrong error message to
     * seeding failing at the sixth starter and taking registration with it.
     *
     * <p>TEN-327 counts user-created rows only. Starters are system-provided, so registration
     * succeeds with all 13 and the account still holds its whole allowance of 5 afterwards — the
     * two halves this asserts. {@code CategoryLimitProperties.free} is unchanged at 5: the cap is a
     * pricing statement, and raising it to clear the seed list was considered and rejected.
     *
     * <p>The per-path behaviour behind this — expense, chat, CSV, recurring — is
     * {@link CategoryCapIncidentalPathsIntegrationTest}.
     */
    @Test
    void free_afterRegistrationSeeding_stillHasItsWholeCategoryAllowance() throws Exception {
        assertThatCode(() -> categorySeedService.seedPredefinedForUser(free))
                .as("registration seeding, with the cap enforced")
                .doesNotThrowAnyException();

        assertThat(categoryRepository.countByUser(free))
                .as("every starter is written")
                .isEqualTo(SEEDED_CATEGORY_COUNT);
        assertThat(categoryRepository.countByUserAndSystemProvidedFalse(free))
                .as("and none of them counts against the cap")
                .isZero();

        for (int i = 1; i <= FREE_CATEGORY_CAP; i++) {
            createCategory(freeAuth, "Mine " + i).andExpect(status().isCreated());
        }
        createCategory(freeAuth, "Mine " + (FREE_CATEGORY_CAP + 1))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.feature").value("CUSTOM_CATEGORIES"));

        assertThat(categoryRepository.countByUser(free))
                .isEqualTo(SEEDED_CATEGORY_COUNT + FREE_CATEGORY_CAP);
    }

    /**
     * TEN-319: the cap binds the expense path too, not only {@code POST /categories}.
     *
     * <p>A user standing exactly at the cap — refused a sixth category by {@code POST /categories}
     * in the same test — is refused the same way when they try to reach the same outcome by naming
     * a new category on an expense. The refusal is a 402 naming {@code CUSTOM_CATEGORIES}, not a
     * 500, and it takes the expense with it: neither the category nor the expense is written.
     */
    @Test
    void free_atTheCategoryCap_isRefusedACategoryThroughTheExpensePath() throws Exception {
        for (int i = 1; i <= FREE_CATEGORY_CAP; i++) {
            createCategory(freeAuth, "Category " + i).andExpect(status().isCreated());
        }
        createCategory(freeAuth, "Sixth By Hand")
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.feature").value("CUSTOM_CATEGORIES"));

        mockMvc.perform(post("/expenses")
                        .header("Authorization", freeAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":150.00,\"description\":\"Lunch at Jollibee\","
                                + "\"category\":\"Sixth By Expense\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.feature").value("CUSTOM_CATEGORIES"));

        assertThat(categoryRepository.findByUserAndNameIgnoreCase(free, "Sixth By Expense")).isEmpty();
        assertThat(categoryRepository.countByUser(free)).isEqualTo(FREE_CATEGORY_CAP);
        assertThat(expenseRepository.findAllByUserOrderByDateDesc(free)).isEmpty();
    }

    /**
     * The other half of the same decision: naming a category the user already has is not a
     * creation, so an expense at the cap goes through. Without this, "the cap binds the expense
     * path" could be satisfied by refusing every expense a capped user writes.
     */
    @Test
    void free_atTheCategoryCap_stillRecordsAnExpenseInAnExistingCategory() throws Exception {
        for (int i = 1; i <= FREE_CATEGORY_CAP; i++) {
            createCategory(freeAuth, "Category " + i).andExpect(status().isCreated());
        }

        mockMvc.perform(post("/expenses")
                        .header("Authorization", freeAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":150.00,\"description\":\"Lunch at Jollibee\","
                                + "\"category\":\"Category 1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("Category 1"));

        assertThat(categoryRepository.countByUser(free)).isEqualTo(FREE_CATEGORY_CAP);
    }

    @Test
    void premium_passesTheFreeCategoryCap() throws Exception {
        for (int i = 1; i <= FREE_CATEGORY_CAP + 1; i++) {
            createCategory(premiumAuth, "Category " + i).andExpect(status().isCreated());
        }
    }

    // ---------------------------------------------------------------- chat personas

    @Test
    void free_premiumPersonas_fallBackToPlain() {
        assertThat(entitlementService.resolveChatMode("professional", free)).isEqualTo("plain");
        assertThat(entitlementService.resolveChatMode("genz", free)).isEqualTo("plain");
        assertThat(entitlementService.resolveChatMode("plain", free)).isEqualTo("plain");
    }

    @Test
    void free_chatRequestForAPremiumPersona_isServedAsPlain() throws Exception {
        // Not 402: NL_CHATBOT is a FREE feature. The persona degrades, the reply still arrives.
        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", freeAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"how much did I spend\",\"mode\":\"genz\"}"))
                .andExpect(status().isOk());

        // Matched on id, not on the User instance: Spring Security loads its own copy per request
        // and User uses identity equality, so eq(free) could never match the argument.
        ArgumentCaptor<String> mode = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<User> caller = ArgumentCaptor.forClass(User.class);
        verify(chatActionService).dispatch(any(), mode.capture(), caller.capture(), any());
        assertThat(mode.getValue()).isEqualTo("plain");
        assertThat(caller.getValue().getId()).isEqualTo(free.getId());
    }

    @Test
    void premiumAndTrial_keepTheirPersona() {
        assertThat(entitlementService.resolveChatMode("professional", premium)).isEqualTo("professional");
        assertThat(entitlementService.resolveChatMode("genz", premium)).isEqualTo("genz");
        assertThat(entitlementService.resolveChatMode("professional", trial)).isEqualTo("professional");
        assertThat(entitlementService.resolveChatMode("genz", trial)).isEqualTo("genz");
    }

    // ---------------------------------------------------------------- admin bypass and view-as

    @Test
    void admin_bypassesEnforcementEntirely() throws Exception {
        mockMvc.perform(post("/ai/query")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"total spent this month\"}"))
                .andExpect(status().isOk());

        // The category cap reads the same describe() the feature gate does, so the bypass covers it too.
        for (int i = 1; i <= FREE_CATEGORY_CAP + 1; i++) {
            createCategory(adminAuth, "Category " + i).andExpect(status().isCreated());
        }

        mockMvc.perform(get("/user/entitlements").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.features.length()").value(FeatureKey.values().length));
    }

    @Test
    void admin_viewingAsFree_isBlockedLikeAFreeUser() throws Exception {
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
    void admin_viewingAsPremium_staysUnlocked() throws Exception {
        mockMvc.perform(post("/ai/query")
                        .header("Authorization", adminAuth)
                        .header("X-View-As-Plan", "PREMIUM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"total spent this month\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void viewAsHeaderFromANonAdmin_isIgnored() throws Exception {
        // The header is an admin preview tool, never an escalation path.
        mockMvc.perform(post("/ai/query")
                        .header("Authorization", freeAuth)
                        .header("X-View-As-Plan", "PREMIUM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"total spent this month\"}"))
                .andExpect(status().isPaymentRequired());
    }

    // ---------------------------------------------------------------- helpers

    private User createUser(String name, String email, Role role) {
        return userRepository.save(User.builder()
                .name(name).email(email).password(passwordEncoder.encode("pw")).role(role).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtUtil.generate(user.getEmail());
    }

    private void subscribe(User user, PlanKey planKey, SubscriptionStatus status) {
        SubscriptionPlan plan = planRepository.findByPlanKey(planKey).orElseThrow();
        userSubscriptionRepository.save(UserSubscription.builder()
                .user(user)
                .plan(plan)
                .status(status)
                .startedAt(LocalDateTime.now())
                .currentPeriodEnd(LocalDateTime.now().plusDays(30))
                .build());
    }

    private org.springframework.test.web.servlet.ResultActions createCategory(String auth, String name) throws Exception {
        return mockMvc.perform(post("/categories")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}"));
    }

    /**
     * Insert {@code count} quota-bearing successful AI calls for this month. Written through JDBC in
     * one batch rather than through the repository because the premium case needs 300 of them, and
     * the quota only counts rows — the fidelity that matters is the four columns it filters on.
     */
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
