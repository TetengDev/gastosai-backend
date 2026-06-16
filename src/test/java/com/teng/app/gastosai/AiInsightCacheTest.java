package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.SqlGenerator;
import com.teng.app.gastosai.dto.ExpenseRequest;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.AiInsightService;
import com.teng.app.gastosai.service.ExpenseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class AiInsightCacheTest {

	@Autowired
	AiInsightService aiInsightService;

	@Autowired
	ExpenseService expenseService;

	@Autowired
	UserRepository userRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@MockitoBean
	SqlGenerator sqlGenerator;

	User user;

	@BeforeEach
	void setUp() {
		userRepository.deleteAll();
		user = userRepository.save(User.builder()
				.name("Cache User").email("cache@test.com")
				.password(passwordEncoder.encode("password")).build());
		when(sqlGenerator.generateInsightSummary(any(), eq("month-summary"), eq("plain")))
				.thenReturn("A quiet month.");
	}

	@Test
	void monthSummary_isCachedThenEvictedOnExpenseChange() throws Exception {
		aiInsightService.getMonthSummary(user, "2026-06");
		aiInsightService.getMonthSummary(user, "2026-06");
		// Second call served from cache — generator invoked once.
		verify(sqlGenerator, times(1)).generateInsightSummary(any(), eq("month-summary"), eq("plain"));

		// An expense change evicts the insight caches.
		expenseService.create(new ExpenseRequest(
				new BigDecimal("100.00"), "Food", LocalDateTime.now(), "Lunch", null, null, null, null), user);

		aiInsightService.getMonthSummary(user, "2026-06");
		verify(sqlGenerator, times(2)).generateInsightSummary(any(), eq("month-summary"), eq("plain"));
	}
}
