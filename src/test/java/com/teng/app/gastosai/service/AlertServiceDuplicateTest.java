package com.teng.app.gastosai.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.Frequency;
import com.teng.app.gastosai.entity.RecurringExpense;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.GlobalExceptionHandler;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.support.PostgresBackedTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TEN-551: two overlapping alert reads for one user inserted the same alert twice, and the
 * duplicate then made every read of that month fail. This races the real service against real
 * Postgres and checks both reads succeed and each key ends with exactly one row.
 *
 * <p>Deliberately not {@code @Transactional}: each thread must run, and commit, its own
 * transaction, or there is no race to observe. The fixtures are committed up front for the same
 * reason. {@link PostgresBackedTest} truncates between tests.
 */
@SpringBootTest
class AlertServiceDuplicateTest extends PostgresBackedTest {

	/** A single round can pass by luck of scheduling; several make a lost race near-certain to show. */
	private static final int ROUNDS = 10;

	@Autowired AlertService alertService;
	@Autowired UserRepository userRepository;
	@Autowired ExpenseRepository expenseRepository;
	@Autowired RecurringExpenseRepository recurringExpenseRepository;
	@Autowired JdbcTemplate jdbcTemplate;

	private final ExecutorService pool = Executors.newFixedThreadPool(2);
	private User user;
	private String month;

	@BeforeEach
	void commitFixtures() {
		user = userRepository.save(User.builder()
				.name("Race").email("race@alerts.test").password("x").build());
		month = YearMonth.now().toString();

		// A spending spike (category key): this month is 10x last month.
		expenseRepository.save(expense(new BigDecimal("100.00"), LocalDateTime.now().minusMonths(1)));
		expenseRepository.save(expense(new BigDecimal("1000.00"), LocalDateTime.now()));

		// A bill due today (recurring key), the shape the local stack duplicated.
		recurringExpenseRepository.save(RecurringExpense.builder()
				.user(user).name("Meal Plan").amount(new BigDecimal("500.00"))
				.frequency(Frequency.MONTHLY).dayOfMonth(LocalDate.now().getDayOfMonth()).active(true)
				.build());
	}

	@AfterEach
	void stopPool() {
		pool.shutdownNow();
	}

	@Test
	void twoConcurrentReadsCreateEachAlertExactlyOnce() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			jdbcTemplate.update("DELETE FROM alerts WHERE user_id = ?", user.getId());

			CyclicBarrier start = new CyclicBarrier(2);
			List<Future<Integer>> reads = List.of(
					pool.submit(() -> {
						start.await();
						return alertService.getOrGenerate(user, month).size();
					}),
					pool.submit(() -> {
						start.await();
						return alertService.getOrGenerate(user, month).size();
					}));

			for (Future<Integer> read : reads) {
				// get() rethrows anything a read threw — the unique violation, or the
				// IncorrectResultSizeDataAccessException a duplicate caused.
				assertThat(read.get(30, TimeUnit.SECONDS)).as("alerts returned in round %d", round).isEqualTo(2);
			}
			assertThat(countAlerts("SPENDING_SPIKE")).as("spike rows after round %d", round).isEqualTo(1);
			assertThat(countAlerts("RECURRING_DUE")).as("recurring rows after round %d", round).isEqualTo(1);
		}
	}

	/**
	 * A duplicate row is the server's fault, never the client's: it must not answer 400 "Query
	 * execution failed", which tells a client the request itself is wrong.
	 */
	@Test
	void aLookupThatFindsSeveralRowsIsA500NotA400() throws Exception {
		AppEventService appEventService = mock(AppEventService.class);
		MockMvc standalone = MockMvcBuilders.standaloneSetup(new DuplicateRowEndpoint())
				.setControllerAdvice(new GlobalExceptionHandler(appEventService))
				.build();

		standalone.perform(get("/duplicate"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
		verify(appEventService).recordError(eq("/duplicate"), eq(500), anyString(), anyString());
	}

	private Expense expense(BigDecimal amount, LocalDateTime date) {
		return Expense.builder()
				.user(user).amount(amount).amountInBaseCurrency(amount)
				.description("race fixture").date(date)
				.build();
	}

	private int countAlerts(String type) {
		return jdbcTemplate.queryForObject(
				"SELECT count(*) FROM alerts WHERE user_id = ? AND type = ? AND alert_month = ?",
				Integer.class, user.getId(), type, month);
	}

	/**
	 * Inner, not static: component scanning skips classes that are not independent, so this stays
	 * out of the Spring context the rest of the suite shares.
	 */
	@RestController
	class DuplicateRowEndpoint {
		@GetMapping("/duplicate")
		String duplicate() {
			throw new IncorrectResultSizeDataAccessException(
					"Query did not return a unique result: 2 results were returned", 1, 2);
		}
	}
}
