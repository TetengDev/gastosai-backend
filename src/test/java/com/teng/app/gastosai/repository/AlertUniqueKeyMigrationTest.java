package com.teng.app.gastosai.repository;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V34 deletes duplicate alerts, keeping the oldest of each key, and adds the unique indexes that
 * stop them coming back (TEN-551). Proved on rows that existed before the migration ran — the only
 * place duplicates can exist once it has.
 *
 * <p>Owns its database, for the reason {@code CrossTenantExpenseRepairMigrationTest} gives: the
 * shared suite container is migrated once and never dropped, so stopping it at V33 would strand
 * every class that ran after this one.
 *
 * <p>The seed covers what the two partial indexes must tell apart: a true duplicate on each key,
 * two RECURRING_DUE alerts that share a category name but not a recurring expense (both must
 * survive), and the same key for another user (not a duplicate).
 */
@Testcontainers
class AlertUniqueKeyMigrationTest {

	@Container
	static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17");

	private static DataSource dataSource;

	private static long alice;
	private static long bob;
	private static long netflix;

	private static long oldestBudgetAlert;
	private static long newerBudgetAlert;
	private static long bobsBudgetAlert;
	private static long oldestRecurringAlert;
	private static long newerRecurringAlert;
	private static long otherRecurringAlertSameCategory;

	@BeforeAll
	static void migrateAndSeed() throws Exception {
		PGSimpleDataSource ds = new PGSimpleDataSource();
		ds.setUrl(DB.getJdbcUrl());
		ds.setUser(DB.getUsername());
		ds.setPassword(DB.getPassword());
		dataSource = ds;

		// Stop at V33: nothing prevents a duplicate yet.
		Flyway.configure().dataSource(ds).target("33").load().migrate();
		try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
			alice = insertUser(s, "alice@alerts.test");
			bob = insertUser(s, "bob@alerts.test");
			netflix = insertRecurring(s, alice, "Netflix");
			long spotify = insertRecurring(s, alice, "Spotify");

			// The newer twin is inserted first, so "keep the oldest" cannot pass by keeping the
			// lowest id.
			newerBudgetAlert = insertAlert(s, alice, "BUDGET_EXCEEDED", "Food", null, "2026-11-19 10:00:01");
			oldestBudgetAlert = insertAlert(s, alice, "BUDGET_EXCEEDED", "Food", null, "2026-11-19 10:00:00");
			bobsBudgetAlert = insertAlert(s, bob, "BUDGET_EXCEEDED", "Food", null, "2026-11-19 10:00:02");

			// The shape found on the local stack: two rows 10ms apart for one recurring expense.
			oldestRecurringAlert = insertAlert(s, alice, "RECURRING_DUE", "Subscriptions", netflix, "2026-11-19 17:50:08.614");
			newerRecurringAlert = insertAlert(s, alice, "RECURRING_DUE", "Subscriptions", netflix, "2026-11-19 17:50:08.624");
			otherRecurringAlertSameCategory = insertAlert(s, alice, "RECURRING_DUE", "Subscriptions", spotify, "2026-11-19 17:50:08.630");
		}
		// Throws if V34 fails — including CREATE UNIQUE INDEX over a duplicate the DELETE missed.
		Flyway.configure().dataSource(ds).target("34").load().migrate();
	}

	@Test
	void keepsTheOldestRowOfEachDuplicatedKeyAndNothingElseIsTouched() throws Exception {
		assertThat(alertIds()).containsExactlyInAnyOrder(
				oldestBudgetAlert, bobsBudgetAlert, oldestRecurringAlert, otherRecurringAlertSameCategory);
		assertThat(alertIds()).doesNotContain(newerBudgetAlert, newerRecurringAlert);
	}

	@Test
	void aSecondAlertOnTheCategoryKeyIsRefused() {
		assertThatThrownBy(() -> {
			try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
				insertAlert(s, alice, "BUDGET_EXCEEDED", "Food", null, "2026-11-19 11:00:00");
			}
		}).isInstanceOf(SQLException.class).hasMessageContaining("ux_alerts_category_key");
	}

	@Test
	void aSecondAlertOnTheRecurringKeyIsRefused() {
		assertThatThrownBy(() -> {
			try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
				insertAlert(s, alice, "RECURRING_DUE", "Subscriptions", netflix, "2026-11-19 11:00:00");
			}
		}).isInstanceOf(SQLException.class).hasMessageContaining("ux_alerts_recurring_key");
	}

	/**
	 * {@code recurring_expense_id} is {@code ON DELETE SET NULL}. Had the category key been scoped
	 * by "recurring id is null", deleting both subscriptions would null their two alerts into it,
	 * and the delete would fail on the index.
	 */
	@Test
	void deletingRecurringExpensesThatShareACategoryStillWorks() throws Exception {
		try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
			long hulu = insertRecurring(s, bob, "Hulu");
			long disney = insertRecurring(s, bob, "Disney");
			insertAlert(s, bob, "RECURRING_DUE", "Streaming", hulu, "2026-11-19 12:00:00");
			insertAlert(s, bob, "RECURRING_DUE", "Streaming", disney, "2026-11-19 12:00:01");

			s.execute("DELETE FROM recurring_expenses WHERE id IN (" + hulu + ", " + disney + ")");

			assertThat(scalar(s, "SELECT count(*) FROM alerts WHERE user_id = " + bob
					+ " AND category_name = 'Streaming' AND recurring_expense_id IS NULL")).isEqualTo(2);
		}
	}

	private static long insertUser(Statement s, String email) throws SQLException {
		s.execute("INSERT INTO users (name, email, password, created_at) VALUES ('"
				+ email + "', '" + email + "', 'x', now())");
		return scalar(s, "SELECT id FROM users WHERE email = '" + email + "'");
	}

	private static long insertRecurring(Statement s, long userId, String name) throws SQLException {
		s.execute("INSERT INTO recurring_expenses (user_id, name, amount, amount_centavos, frequency, day_of_month)"
				+ " VALUES (" + userId + ", '" + name + "', 100, 10000, 'MONTHLY', 20)");
		return scalar(s, "SELECT id FROM recurring_expenses WHERE user_id = " + userId + " AND name = '" + name + "'");
	}

	private static long insertAlert(Statement s, long userId, String type, String categoryName,
			Long recurringExpenseId, String createdAt) throws SQLException {
		try (ResultSet rs = s.executeQuery("INSERT INTO alerts (user_id, type, alert_month, category_name, message,"
				+ " severity, recurring_expense_id, created_at) VALUES (" + userId + ", '" + type + "', '2026-11', '"
				+ categoryName + "', 'msg', 'INFO', " + recurringExpenseId + ", '" + createdAt + "') RETURNING id")) {
			rs.next();
			return rs.getLong(1);
		}
	}

	private static List<Long> alertIds() throws SQLException {
		List<Long> ids = new ArrayList<>();
		try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
				ResultSet rs = s.executeQuery("SELECT id FROM alerts WHERE category_name IN ('Food', 'Subscriptions')")) {
			while (rs.next()) {
				ids.add(rs.getLong(1));
			}
		}
		return ids;
	}

	private static long scalar(Statement s, String query) throws SQLException {
		try (ResultSet rs = s.executeQuery(query)) {
			assertThat(rs.next()).as("no row for: %s", query).isTrue();
			return rs.getLong(1);
		}
	}
}
