package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.ChatAuditLogRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.ChatAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class AdminChatAuditApiIntegrationTest {

	@Autowired WebApplicationContext webApplicationContext;
	@Autowired UserRepository userRepository;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired JwtUtil jwtUtil;
	@Autowired ChatAuditService chatAuditService;
	@Autowired ChatAuditLogRepository chatAuditLogRepository;

	MockMvc mockMvc;
	String adminAuth;
	String userAuth;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
		chatAuditLogRepository.deleteAll();
		userRepository.deleteAll();
		User admin = userRepository.save(User.builder().name("Admin").email("admin@test.com")
				.password(passwordEncoder.encode("password")).role(Role.ADMIN).build());
		User normal = userRepository.save(User.builder().name("Norm").email("norm@test.com")
				.password(passwordEncoder.encode("password")).role(Role.USER).build());
		adminAuth = "Bearer " + jwtUtil.generate(admin.getEmail());
		userAuth = "Bearer " + jwtUtil.generate(normal.getEmail());
		chatAuditService.record(normal.getId(), null, "create_expense", AiUsageStatus.SUCCESS, null);
	}

	@Test
	void admin_seesAuditTrail() throws Exception {
		mockMvc.perform(get("/admin/chat-audit").header("Authorization", adminAuth))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].toolName").value("create_expense"));
	}

	@Test
	void normalUser_isForbidden() throws Exception {
		mockMvc.perform(get("/admin/chat-audit").header("Authorization", userAuth))
				.andExpect(status().isForbidden());
	}

	@Test
	void unauthenticated_isRejected() throws Exception {
		mockMvc.perform(get("/admin/chat-audit"))
				.andExpect(status().is4xxClientError());
	}
}
