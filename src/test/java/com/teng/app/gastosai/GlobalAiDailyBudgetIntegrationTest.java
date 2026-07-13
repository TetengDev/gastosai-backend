package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.entity.AiUsage;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.AiQuotaExceededException;
import com.teng.app.gastosai.repository.AiUsageRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.AiQuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "gastos.ai.global-daily-max=3",
        "gastos.ai.absolute-monthly-cap=10000",
        "gastos.ai.allow-shared-key=true"
})
class GlobalAiDailyBudgetIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired AiUsageRepository aiUsageRepository;
    @Autowired AiQuotaService aiQuotaService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    User regularUser;
    User adminUser;

    @BeforeEach
    void setUp() {
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
