package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.AiUsage;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.entity.AppEvent;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.AiUsageRepository;
import com.teng.app.gastosai.repository.AppEventRepository;
import com.teng.app.gastosai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class AdminObservabilityApiIntegrationTest {

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired AiUsageRepository aiUsageRepository;
    @Autowired AppEventRepository appEventRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtUtil jwtUtil;

    MockMvc mockMvc;
    String adminAuth;
    String userAuth;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        appEventRepository.deleteAll();
        aiUsageRepository.deleteAll();
        userRepository.deleteAll();

        User admin = userRepository.save(User.builder().name("Admin").email("admin@test.com")
                .password(passwordEncoder.encode("password")).role(Role.ADMIN).build());
        User normal = userRepository.save(User.builder().name("Norm").email("norm@test.com")
                .password(passwordEncoder.encode("password")).role(Role.USER).build());
        adminAuth = "Bearer " + jwtUtil.generate(admin.getEmail());
        userAuth = "Bearer " + jwtUtil.generate(normal.getEmail());

        aiUsageRepository.save(AiUsage.builder().userId(normal.getId()).provider("openai")
                .model("gpt-4o-mini").feature(AiFeature.CHAT_CRUD_ASSISTANT)
                .inputTokens(100).outputTokens(50).totalTokens(150)
                .estimatedCostUsd(new BigDecimal("0.000045")).status(AiUsageStatus.SUCCESS).build());
        aiUsageRepository.save(AiUsage.builder().userId(normal.getId()).provider("openai")
                .model("gpt-4o-mini").feature(AiFeature.CHAT_CRUD_ASSISTANT)
                .status(AiUsageStatus.FAILED).build());

        appEventRepository.save(AppEvent.builder().eventType("WRITE_RATE_LIMIT").severity("WARN")
                .userId(normal.getId()).path("/expenses").httpStatus(429).message("slow down").build());
    }

    @Test
    void summary_admin_returnsCounts() throws Exception {
        mockMvc.perform(get("/admin/observability/summary").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(2))
                .andExpect(jsonPath("$.signupsToday").value(2))
                .andExpect(jsonPath("$.activeUsers24h").value(1))
                .andExpect(jsonPath("$.topUsers30d.length()").value(1))
                .andExpect(jsonPath("$.topUsers30d[0].requests").value(2));
    }

    @Test
    void cost_admin_returnsTodayAndMonthToDate() throws Exception {
        mockMvc.perform(get("/admin/observability/cost").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successToday").value(1))
                .andExpect(jsonPath("$.failedToday").value(1))
                .andExpect(jsonPath("$.monthToDate.length()").value(1));
    }

    @Test
    void events_admin_returnsRecent() throws Exception {
        mockMvc.perform(get("/admin/observability/events").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventType").value("WRITE_RATE_LIMIT"));
    }

    @Test
    void events_admin_filtersByType() throws Exception {
        mockMvc.perform(get("/admin/observability/events").param("type", "SERVER_ERROR").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void health_admin_reportsDbUpAndVersion() throws Exception {
        mockMvc.perform(get("/admin/observability/health").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dbUp").value(true))
                .andExpect(jsonPath("$.version").exists());
    }

    @Test
    void normalUser_isForbidden() throws Exception {
        mockMvc.perform(get("/admin/observability/summary").header("Authorization", userAuth))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticated_isRejected() throws Exception {
        mockMvc.perform(get("/admin/observability/summary"))
                .andExpect(status().is4xxClientError());
    }
}
