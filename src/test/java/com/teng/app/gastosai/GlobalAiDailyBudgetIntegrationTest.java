package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.entity.AiUsage;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.AiQuotaExceededException;
import com.teng.app.gastosai.repository.AiUsageRepository;
import com.teng.app.gastosai.repository.AppEventRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.AiQuotaService;
import com.teng.app.gastosai.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "gastos.ai.global-daily-max=3",
        "gastos.ai.absolute-monthly-cap=10000",
        "gastos.ai.allow-shared-key=true"
})
class GlobalAiDailyBudgetIntegrationTest extends PostgresBackedTest {

    @Autowired UserRepository userRepository;
    @Autowired AiUsageRepository aiUsageRepository;
    @Autowired AppEventRepository appEventRepository;
    @Autowired AiQuotaService aiQuotaService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    User regularUser;
    User adminUser;

    @BeforeEach
    void setUp() {
        appEventRepository.deleteAll();
        aiUsageRepository.deleteAll();
        userRepository.deleteAll();

        regularUser = userRepository.save(User.builder()
                .name("Regular").email("regular@test.com")
                .password(passwordEncoder.encode("pw")).role(Role.USER).build());

        adminUser = userRepository.save(User.builder()
                .name("Admin").email("admin@test.com")
                .password(passwordEncoder.encode("pw")).role(Role.ADMIN).build());
    }

    private void seedSuccessRow(Long userId) {
        aiUsageRepository.save(AiUsage.builder()
                .userId(userId)
                .provider("openai")
                .model("gpt-4o-mini")
                .feature(AiFeature.CHAT_CRUD_ASSISTANT)
                .status(AiUsageStatus.SUCCESS)
                .build());
    }

    private void seedSuccessRowYesterday(Long userId) {
        jdbcTemplate.update(
                "INSERT INTO ai_usage (user_id, provider, model, feature, status, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "openai", "gpt-4o-mini", "CHAT_CRUD_ASSISTANT", "SUCCESS",
                LocalDateTime.now().minusDays(1));
    }

    @Test
    void globalCapNotReached_regularUser_noException() {
        seedSuccessRow(regularUser.getId());
        seedSuccessRow(regularUser.getId());

        assertThatCode(() -> aiQuotaService.assertWithinQuota(regularUser, AiFeature.CHAT_CRUD_ASSISTANT))
                .doesNotThrowAnyException();
    }

    @Test
    void globalCapReached_regularUser_throwsQuotaExceeded() {
        seedSuccessRow(regularUser.getId());
        seedSuccessRow(regularUser.getId());
        seedSuccessRow(regularUser.getId());

        assertThatThrownBy(() -> aiQuotaService.assertWithinQuota(regularUser, AiFeature.CHAT_CRUD_ASSISTANT))
                .isInstanceOf(AiQuotaExceededException.class);

        assertThat(appEventRepository.findAll())
                .anyMatch(e -> "AI_GLOBAL_CAP".equals(e.getEventType())
                        && "WARN".equals(e.getSeverity())
                        && regularUser.getId().equals(e.getUserId()));
    }

    @Test
    void globalCapReached_adminUser_noException() {
        seedSuccessRow(regularUser.getId());
        seedSuccessRow(regularUser.getId());
        seedSuccessRow(regularUser.getId());

        assertThatCode(() -> aiQuotaService.assertWithinQuota(adminUser, AiFeature.CHAT_CRUD_ASSISTANT))
                .doesNotThrowAnyException();
    }

    @Test
    void oldSuccessRows_notCountedAgainstDailyBudget() {
        seedSuccessRowYesterday(regularUser.getId());
        seedSuccessRowYesterday(regularUser.getId());
        seedSuccessRowYesterday(regularUser.getId());

        seedSuccessRow(regularUser.getId());
        seedSuccessRow(regularUser.getId());

        assertThatCode(() -> aiQuotaService.assertWithinQuota(regularUser, AiFeature.CHAT_CRUD_ASSISTANT))
                .doesNotThrowAnyException();
    }
}

/**
 * TEN-244 regression: the same caps, in the shipping default configuration.
 *
 * <p>{@code gastos.ai.allow-shared-key=false} means every request is served by the user's own key,
 * so the platform spends nothing at the provider on it. {@code globalDailyMax} is a shared pool
 * metered against <em>our</em> key, so in this mode it must not fire — before the fix it was checked
 * above the managed-mode guard, and one heavy user's traffic locked out everybody else.
 *
 * <p>{@code absoluteMonthlyCap} is the deliberate exception, and this class pins that too. It is
 * per-user and guards the backend rather than the provider bill, so it still fires here. The unit
 * test {@code AiQuotaServiceTest.absoluteCap_blocks_whenAtCap_byoMode} asserts the same thing; the
 * two caps were conflated when this issue was first written up.
 *
 * <p>A second top-level class rather than a {@code @Nested} one: the property differs, which means
 * a different Spring context, and {@code @TestPropertySource} is type-level only. Spring injects
 * only the innermost instance of a {@code @Nested} hierarchy, so a nested class cannot reuse the
 * enclosing class's autowired fixtures anyway.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "gastos.ai.global-daily-max=3",
        "gastos.ai.absolute-monthly-cap=2",
        "gastos.ai.allow-shared-key=false"
})
class GlobalAiDailyBudgetByoKeyIntegrationTest extends PostgresBackedTest {

    @Autowired UserRepository userRepository;
    @Autowired AiUsageRepository aiUsageRepository;
    @Autowired AppEventRepository appEventRepository;
    @Autowired AiQuotaService aiQuotaService;
    @Autowired PasswordEncoder passwordEncoder;

    User heavyUser;
    User byoUser;

    @BeforeEach
    void setUp() {
        heavyUser = userRepository.save(User.builder()
                .name("Heavy").email("heavy@test.com")
                .password(passwordEncoder.encode("pw")).role(Role.USER).build());

        byoUser = userRepository.save(User.builder()
                .name("BYO").email("byo@test.com")
                .password(passwordEncoder.encode("pw")).role(Role.USER).build());
    }

    private void seedSuccessRow(Long userId) {
        aiUsageRepository.save(AiUsage.builder()
                .userId(userId)
                .provider("openai")
                .model("gpt-4o-mini")
                .feature(AiFeature.CHAT_CRUD_ASSISTANT)
                .status(AiUsageStatus.SUCCESS)
                .build());
    }

    /** One user drives the platform-wide daily counter past the cap; a second user is unaffected. */
    @Test
    void oneUserExhaustsGlobalPool_otherUserUnaffected() {
        // global-daily-max=3, and every row belongs to the heavy user
        seedSuccessRow(heavyUser.getId());
        seedSuccessRow(heavyUser.getId());
        seedSuccessRow(heavyUser.getId());
        seedSuccessRow(heavyUser.getId());

        assertThat(aiQuotaService.globalDailyUsed()).isGreaterThanOrEqualTo(3);
        assertThat(aiQuotaService.usedThisMonth(byoUser.getId())).isZero();
        // what GET /ai/usage reports to this user: no managed quota applies
        assertThat(aiQuotaService.managedActive()).isFalse();

        assertThatCode(() -> aiQuotaService.assertWithinQuota(byoUser, AiFeature.CHAT_CRUD_ASSISTANT))
                .doesNotThrowAnyException();

        // and nothing was logged as abuse against a user who made no request
        assertThat(appEventRepository.findAll())
                .noneMatch(e -> "AI_GLOBAL_CAP".equals(e.getEventType())
                        && byoUser.getId().equals(e.getUserId()));
    }

    /**
     * The per-user absolute monthly cap is an abuse valve on our backend, not a guard on our
     * provider bill, so it still fires on the user's own key. This is the half of TEN-244 that is
     * deliberately <em>not</em> exempted — see the class javadoc.
     */
    @Test
    void absoluteMonthlyCapStillEnforced() {
        seedSuccessRow(byoUser.getId());
        seedSuccessRow(byoUser.getId());

        assertThat(aiQuotaService.usedThisMonth(byoUser.getId()))
                .isGreaterThanOrEqualTo(2); // at absolute-monthly-cap=2
        assertThat(aiQuotaService.managedActive()).isFalse();

        assertThatThrownBy(() -> aiQuotaService.assertWithinQuota(byoUser, AiFeature.CHAT_CRUD_ASSISTANT))
                .isInstanceOf(AiQuotaExceededException.class);
    }
}
