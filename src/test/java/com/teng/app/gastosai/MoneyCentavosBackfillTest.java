package com.teng.app.gastosai;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V24 backfills every money column into its centavos twin. This proves it, on rows that existed
 * before the migration ran — which is the case the trigger cannot cover and the one that actually
 * happened in production data.
 *
 * <p><strong>This test owns its database, and must.</strong> Every other test shares one container
 * via {@code jdbc:tc:postgresql:17:///gastosaitest?TC_DAEMON=true}, and {@code PostgresBackedTest}
 * documents that the schema is created once and never dropped. Migrating that database to V23
 * would leave it at V23 for every class that ran afterwards, so the breakage would surface far
 * from its cause. A private container keeps the stepwise migration contained.
 *
 * <p>The seeded values are chosen for the residues an ordinary seeder never produces. PostgreSQL's
 * {@code round()} on NUMERIC is half-away-from-zero, not banker's rounding, so 10.1250 and 10.1350
 * both round away from zero rather than to even — asserting both pins the behaviour rather than
 * assuming it.
 */
@Testcontainers
class MoneyCentavosBackfillTest {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17");

    /** decimal literal -> expected centavos, once V24 has run. */
    private record Money(String decimal, long centavos) {}

    private static final List<Money> CASES = List.of(
            new Money("10.1250", 1013),   // exact half-centavo tie, odd result under half-up
            new Money("10.1350", 1014),   // the other parity: banker's rounding would give 1013
            new Money("0.0000", 0),
            new Money("0.0049", 0),       // rounds down to nothing
            new Money("0.0050", 1),       // the smallest tie there is
            new Money("999999.9999", 100000000L),
            new Money("1900.0000", 190000));

    private static DataSource dataSource;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(DB.getJdbcUrl());
        ds.setUser(DB.getUsername());
        ds.setPassword(DB.getPassword());
        dataSource = ds;

        // Stop at V23: the columns exist, nothing has been backfilled, and no trigger is watching.
        Flyway.configure().dataSource(ds).target("23").load().migrate();
        seedRowsThatPredateTheBackfill();
        Flyway.configure().dataSource(ds).target("24").load().migrate();
    }

    /**
     * Rows written while the schema is at V23. Their centavos columns are null on insert, so
     * whatever they hold afterwards was put there by V24's UPDATE and not by its trigger.
     */
    private static void seedRowsThatPredateTheBackfill() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO users (id, name, email, password, created_at) "
                    + "VALUES (1, 'Backfill', 'backfill@test.dev', 'x', now())");
            s.execute("INSERT INTO categories (id, name, user_id) "
                    + "VALUES (1, 'Backfill Category', 1)");

            for (int i = 0; i < CASES.size(); i++) {
                String amount = CASES.get(i).decimal();
                int id = i + 1;
                s.execute("INSERT INTO expenses (id, user_id, description, amount, "
                        + "amount_in_base_currency, date) VALUES (" + id + ", 1, 'e" + id + "', "
                        + amount + ", " + amount + ", now())");
                s.execute("INSERT INTO recurring_expenses (id, user_id, name, amount, frequency) "
                        + "VALUES (" + id + ", 1, 'r" + id + "', " + amount + ", 'MONTHLY')");
                s.execute("INSERT INTO budgets (id, user_id, category_id, budget_month, "
                        + "amount_limit, amount_limit_in_base_currency) VALUES (" + id
                        + ", 1, 1, '2026-0" + ((i % 9) + 1) + "', " + amount + ", " + amount + ")");
                s.execute("INSERT INTO savings_goals (id, user_id, name, target_amount, "
                        + "saved_amount, created_at) VALUES (" + id + ", 1, 'g" + id + "', "
                        + amount + ", " + amount + ", now())");
            }
            // budget_rules is one row per user, so it carries a single case.
            s.execute("INSERT INTO budget_rules (id, user_id, rule_type, monthly_income, "
                    + "needs_pct, wants_pct, savings_pct) VALUES (1, 1, 'FIFTY_THIRTY_TWENTY', "
                    + CASES.get(0).decimal() + ", 50, 30, 20)");
        }
    }

    /** Guards against the whole test passing because nothing was seeded. */
    @Test
    void theSeededRowsExist() throws Exception {
        assertThat(count("expenses")).isEqualTo(CASES.size());
        assertThat(count("recurring_expenses")).isEqualTo(CASES.size());
        assertThat(count("budgets")).isEqualTo(CASES.size());
        assertThat(count("savings_goals")).isEqualTo(CASES.size());
        assertThat(count("budget_rules")).isEqualTo(1);
    }

    @Test
    void everyCentavosColumnEqualsRoundedDecimal() throws Exception {
        assertBackfilled("expenses", "amount", "amount_centavos");
        assertBackfilled("expenses", "amount_in_base_currency", "amount_in_base_currency_centavos");
        assertBackfilled("recurring_expenses", "amount", "amount_centavos");
        assertBackfilled("budgets", "amount_limit", "amount_limit_centavos");
        assertBackfilled("budgets", "amount_limit_in_base_currency",
                "amount_limit_in_base_currency_centavos");
        assertBackfilled("budget_rules", "monthly_income", "monthly_income_centavos");
        assertBackfilled("savings_goals", "target_amount", "target_amount_centavos");
        assertBackfilled("savings_goals", "saved_amount", "saved_amount_centavos");
    }

    @Test
    void noCentavosColumnWasLeftNull() throws Exception {
        assertNoNulls("expenses", "amount_centavos", "amount_in_base_currency_centavos");
        assertNoNulls("recurring_expenses", "amount_centavos");
        assertNoNulls("budgets", "amount_limit_centavos", "amount_limit_in_base_currency_centavos");
        assertNoNulls("budget_rules", "monthly_income_centavos");
        assertNoNulls("savings_goals", "target_amount_centavos", "saved_amount_centavos");
    }

    /** The half-centavo ties, named individually so a failure says which residue broke. */
    @Test
    void halfCentavoTiesRoundAwayFromZero() throws Exception {
        assertThat(centavosFor("10.1250")).isEqualTo(1013);
        assertThat(centavosFor("10.1350")).isEqualTo(1014);
        assertThat(centavosFor("0.0050")).isEqualTo(1);
        assertThat(centavosFor("0.0049")).isEqualTo(0);
    }

    /** A row written *after* V24 is the trigger's job, not the UPDATE's. Both must agree. */
    @Test
    void theTriggerKeepsAgreeingWithTheBackfillForNewRows() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO expenses (id, user_id, description, amount, "
                    + "amount_in_base_currency, date) VALUES (9001, 1, 'after', 10.1250, "
                    + "10.1250, now())");
            try (ResultSet rs = s.executeQuery(
                    "SELECT amount_centavos FROM expenses WHERE id = 9001")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(1013);
            }
        }
    }

    private static long centavosFor(String decimal) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT amount_centavos FROM expenses "
                     + "WHERE amount = " + decimal + " LIMIT 1")) {
            assertThat(rs.next()).as("a seeded row with amount %s", decimal).isTrue();
            return rs.getLong(1);
        }
    }

    private static int count(String table) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void assertBackfilled(String table, String decimalCol, String centavosCol)
            throws Exception {
        List<String> wrong = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, " + decimalCol + ", " + centavosCol
                     + " FROM " + table + " ORDER BY id")) {
            while (rs.next()) {
                BigDecimal decimal = rs.getBigDecimal(2);
                long actual = rs.getLong(3);
                long expected = decimal.movePointRight(2)
                        .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
                if (actual != expected) {
                    wrong.add("id=" + rs.getLong(1) + " " + decimalCol + "=" + decimal
                            + " expected " + expected + " but was " + actual);
                }
            }
        }
        assertThat(wrong).as("%s.%s backfilled from %s", table, centavosCol, decimalCol).isEmpty();
    }

    private static void assertNoNulls(String table, String... centavosCols) throws Exception {
        for (String col : centavosCols) {
            try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT count(*) FROM " + table + " WHERE " + col + " IS NULL")) {
                rs.next();
                assertThat(rs.getInt(1)).as("null %s.%s after the backfill", table, col).isZero();
            }
        }
    }
}
