package com.teng.app.gastosai;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V29 repairs expenses whose category or project belongs to somebody else. This proves it on rows
 * that existed before the migration ran — the only way those rows can exist, since TEN-314 closed
 * the path that wrote them.
 *
 * <p><strong>This test owns its database, and must</strong>, for the reason
 * {@link MoneyCentavosBackfillTest} spells out: the rest of the suite shares one container whose
 * schema is migrated once and never dropped, so stopping it at V28 would strand every class that
 * ran afterwards. A private container keeps the stepwise migration contained.
 *
 * <p>The seeded rows are chosen for the branches a hand-run cannot reach by accident: two foreign
 * categories whose names differ only in case (the {@code DISTINCT ON} must clone one, not two), an
 * owner who already holds the name in a different case (the {@code NOT EXISTS} must reuse it, not
 * clone), a row that already agrees (must not move), and a foreign row belonging to a third user
 * (must stay theirs).
 */
@Testcontainers
class CrossTenantExpenseRepairMigrationTest {

	@Container
	static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17");

	private static DataSource dataSource;

	private static long alice;
	private static long bob;
	private static long carol;

	/** The category ids as seeded, so the assertions can say what moved and what did not. */
	private static long bobsFood;
	private static long bobsSnacks;
	private static long carolsSnacks;
	private static long alicesFoodLowerCase;
	private static long bobsAcme;
	private static long carolsBeta;
	private static long alicesAcmeLowerCase;

	private static long expenseNeedingReuse;
	private static long expenseNeedingClone;
	private static long expenseSharingTheClone;
	private static long expenseAlreadyCorrect;
	private static long bobsOwnExpense;

	@BeforeAll
	static void migrateAndSeed() throws Exception {
		org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
		ds.setUrl(DB.getJdbcUrl());
		ds.setUser(DB.getUsername());
		ds.setPassword(DB.getPassword());
		dataSource = ds;

		// Stop at V28: the shape V29 repairs is reachable, and nothing has repaired it yet.
		Flyway.configure().dataSource(ds).target("28").load().migrate();
		seedRowsThatPredateTheRepair();
		// Throws if V29 fails — including from its own fail-closed DO block.
		Flyway.configure().dataSource(ds).target("29").load().migrate();
	}

	private static void seedRowsThatPredateTheRepair() throws Exception {
		try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
			alice = insertUser(s, "alice@repair.test");
			bob = insertUser(s, "bob@repair.test");
			carol = insertUser(s, "carol@repair.test");

			bobsFood = insertCategory(s, "Food", "burger", bob);
			bobsSnacks = insertCategory(s, "Snacks", "cookie", bob);
			carolsSnacks = insertCategory(s, "snacks", null, carol);
			// Alice already has the name Bob's "Food" carries, spelled differently.
			alicesFoodLowerCase = insertCategory(s, "food", null, alice);

			bobsAcme = insertProject(s, "Acme", bob);
			carolsBeta = insertProject(s, "Beta", carol);
			alicesAcmeLowerCase = insertProject(s, "acme", alice);

			// Alice's expense on Bob's "Food" and Bob's "Acme": both names she already holds in
			// another case, so both must be reused rather than cloned.
			expenseNeedingReuse = insertExpense(s, alice, bobsFood, bobsAcme, "reuse");
			// Bob's "Snacks" and Carol's "snacks" are one name to Alice: one clone, shared.
			expenseNeedingClone = insertExpense(s, alice, bobsSnacks, carolsBeta, "clone");
			expenseSharingTheClone = insertExpense(s, alice, carolsSnacks, null, "clone twin");
			// Already agrees; the repair must leave it exactly where it is.
			expenseAlreadyCorrect = insertExpense(s, alice, alicesFoodLowerCase, alicesAcmeLowerCase, "correct");
			// Bob's own row on Bob's own category: not a disagreement, must not move.
			bobsOwnExpense = insertExpense(s, bob, bobsFood, bobsAcme, "bob own");
		}
	}

	private static long insertUser(Statement s, String email) throws Exception {
		s.execute("INSERT INTO users (name, email, password, created_at) VALUES ('"
				+ email + "', '" + email + "', 'x', now())");
		return id(s, "SELECT id FROM users WHERE email = '" + email + "'");
	}

	private static long insertCategory(Statement s, String name, String icon, long userId) throws Exception {
		s.execute("INSERT INTO categories (name, icon, user_id) VALUES ('" + name + "', "
				+ (icon == null ? "NULL" : "'" + icon + "'") + ", " + userId + ")");
		return id(s, "SELECT id FROM categories WHERE name = '" + name + "' AND user_id = " + userId);
	}

	private static long insertProject(Statement s, String name, long userId) throws Exception {
		s.execute("INSERT INTO projects (name, user_id) VALUES ('" + name + "', " + userId + ")");
		return id(s, "SELECT id FROM projects WHERE name = '" + name + "' AND user_id = " + userId);
	}

	private static long insertExpense(Statement s, long userId, long categoryId, Long projectId,
			String description) throws Exception {
		s.execute("INSERT INTO expenses (user_id, category_id, project_id, description, amount, "
				+ "amount_in_base_currency, date) VALUES (" + userId + ", " + categoryId + ", "
				+ (projectId == null ? "NULL" : projectId) + ", '" + description + "', 100, 100, now())");
		return id(s, "SELECT id FROM expenses WHERE user_id = " + userId + " AND description = '"
				+ description + "'");
	}

	private static long id(Statement s, String query) throws Exception {
		try (ResultSet rs = s.executeQuery(query)) {
			assertThat(rs.next()).as("no row for: %s", query).isTrue();
			return rs.getLong(1);
		}
	}

	// ------------------------------------------------------------------ tests

	/** Guards against the whole class passing because nothing was seeded. */
	@Test
	void theSeededRowsExist() throws Exception {
		assertThat(scalar("SELECT count(*) FROM expenses")).isEqualTo(5);
		assertThat(scalar("SELECT count(*) FROM users")).isEqualTo(3);
	}

	@Test
	void noExpenseDisagreesWithItsCategoryOrProjectAfterTheRepair() throws Exception {
		assertThat(scalar("""
				SELECT count(*) FROM expenses e
				LEFT JOIN categories c ON c.id = e.category_id
				LEFT JOIN projects p ON p.id = e.project_id
				WHERE (c.id IS NOT NULL AND c.user_id <> e.user_id)
				   OR (p.id IS NOT NULL AND p.user_id <> e.user_id)
				""")).isZero();
	}

	@Test
	void anOwnerWhoAlreadyHoldsTheNameKeepsIt_ratherThanGainingASecondSpelling() throws Exception {
		assertThat(categoryOf(expenseNeedingReuse)).isEqualTo(alicesFoodLowerCase);
		assertThat(projectOf(expenseNeedingReuse)).isEqualTo(alicesAcmeLowerCase);
		assertThat(scalar("SELECT count(*) FROM categories WHERE user_id = " + alice
				+ " AND lower(name) = 'food'"))
				.as("a second 'Food' for Alice would be the case-insensitive check failing")
				.isEqualTo(1);
		assertThat(scalar("SELECT count(*) FROM projects WHERE user_id = " + alice
				+ " AND lower(name) = 'acme'")).isEqualTo(1);
	}

	@Test
	void twoForeignCategoriesDifferingOnlyInCase_produceOneCloneTheOwnerShares() throws Exception {
		assertThat(scalar("SELECT count(*) FROM categories WHERE user_id = " + alice
				+ " AND lower(name) = 'snacks'"))
				.as("DISTINCT ON must collapse 'Snacks' and 'snacks' into one row for Alice")
				.isEqualTo(1);
		long clone = categoryOf(expenseNeedingClone);
		assertThat(clone).isEqualTo(categoryOf(expenseSharingTheClone));
		assertThat(clone).isNotIn(bobsSnacks, carolsSnacks);
		assertThat(ownerOfCategory(clone)).isEqualTo(alice);
		assertThat(text("SELECT icon FROM categories WHERE id = " + clone))
				.as("the clone carries the icon it was cloned from")
				.isEqualTo("cookie");
	}

	@Test
	void aProjectTheOwnerDoesNotHaveIsClonedRatherThanDropped() throws Exception {
		long clone = projectOf(expenseNeedingClone);
		assertThat(clone).isNotEqualTo(carolsBeta);
		assertThat(ownerOfProject(clone)).isEqualTo(alice);
		assertThat(text("SELECT name FROM projects WHERE id = " + clone)).isEqualTo("Beta");
		assertThat(projectOf(expenseSharingTheClone)).as("a null tag stays null").isNull();
	}

	@Test
	void rowsThatAlreadyAgreedAreLeftAlone() throws Exception {
		assertThat(categoryOf(expenseAlreadyCorrect)).isEqualTo(alicesFoodLowerCase);
		assertThat(projectOf(expenseAlreadyCorrect)).isEqualTo(alicesAcmeLowerCase);
		assertThat(categoryOf(bobsOwnExpense)).isEqualTo(bobsFood);
		assertThat(projectOf(bobsOwnExpense)).isEqualTo(bobsAcme);
	}

	@Test
	void theForeignRowsStayWithTheirOwners() throws Exception {
		assertThat(ownerOfCategory(bobsFood)).isEqualTo(bob);
		assertThat(ownerOfCategory(bobsSnacks)).isEqualTo(bob);
		assertThat(ownerOfCategory(carolsSnacks)).isEqualTo(carol);
		assertThat(ownerOfProject(bobsAcme)).isEqualTo(bob);
		assertThat(ownerOfProject(carolsBeta)).isEqualTo(carol);
		assertThat(scalar("SELECT count(*) FROM categories WHERE user_id = " + carol)).isEqualTo(1);
	}

	/** The chain is applied, not merely attempted: a failed DO block would leave V29 unapplied. */
	@Test
	void v29IsRecordedAsApplied() throws Exception {
		List<String> applied = new ArrayList<>();
		try (Connection c = dataSource.getConnection();
				Statement s = c.createStatement();
				ResultSet rs = s.executeQuery(
						"SELECT version FROM flyway_schema_history WHERE success = true")) {
			while (rs.next()) {
				applied.add(rs.getString(1));
			}
		}
		assertThat(applied).contains("29");
		assertThat(scalar("SELECT count(*) FROM flyway_schema_history WHERE success = false")).isZero();
	}

	// ------------------------------------------------------------------ jdbc

	private long scalar(String sql) throws Exception {
		try (Connection c = dataSource.getConnection();
				Statement s = c.createStatement();
				ResultSet rs = s.executeQuery(sql)) {
			assertThat(rs.next()).isTrue();
			return rs.getLong(1);
		}
	}

	private String text(String sql) throws Exception {
		try (Connection c = dataSource.getConnection();
				Statement s = c.createStatement();
				ResultSet rs = s.executeQuery(sql)) {
			assertThat(rs.next()).isTrue();
			return rs.getString(1);
		}
	}

	private Long nullableScalar(String sql) throws Exception {
		try (Connection c = dataSource.getConnection();
				Statement s = c.createStatement();
				ResultSet rs = s.executeQuery(sql)) {
			assertThat(rs.next()).isTrue();
			long value = rs.getLong(1);
			return rs.wasNull() ? null : value;
		}
	}

	private Long categoryOf(long expenseId) throws Exception {
		return nullableScalar("SELECT category_id FROM expenses WHERE id = " + expenseId);
	}

	private Long projectOf(long expenseId) throws Exception {
		return nullableScalar("SELECT project_id FROM expenses WHERE id = " + expenseId);
	}

	private long ownerOfCategory(long categoryId) throws Exception {
		return scalar("SELECT user_id FROM categories WHERE id = " + categoryId);
	}

	private long ownerOfProject(long projectId) throws Exception {
		return scalar("SELECT user_id FROM projects WHERE id = " + projectId);
	}
}
