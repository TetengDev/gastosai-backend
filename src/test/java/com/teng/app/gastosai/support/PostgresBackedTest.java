package com.teng.app.gastosai.support;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Base class for tests that touch the database.
 *
 * <p>The suite shares a single PostgreSQL container across every Spring context (see
 * {@code src/test/resources/application.properties}), and Flyway — not {@code ddl-auto} — owns its
 * schema. Two consequences follow, and this class exists for the second:
 *
 * <ul>
 *   <li>the schema is created once and never dropped, so nothing wipes rows between test classes
 *       the way {@code create-drop} against H2 used to on every context refresh;
 *   <li>a class that deletes only the tables it wrote leaves the rest behind, and the next class's
 *       {@code deleteAll()} then fails on a foreign key pointing at a row it never created.
 * </ul>
 *
 * <p>So isolation is explicit: every test starts from an empty database. {@code TRUNCATE} of all
 * application tables in one statement is both faster than per-repository deletes and immune to
 * ordering — {@code CASCADE} follows the foreign keys, and {@code RESTART IDENTITY} keeps
 * generated ids from drifting across classes, which some assertions would otherwise notice.
 *
 * <p>{@code flyway_schema_history} is excluded on purpose: truncating it would make Flyway
 * re-apply the whole chain on the next context refresh against a schema that already exists.
 *
 * <p>Subclasses that are themselves {@code @Transactional} still work — the truncation joins the
 * test's transaction and rolls back with it.
 *
 * <p>Deliberately not extended by {@code AppDataLoaderIntegrationTest}, whose subject is the data
 * seeded at context startup: truncating before each test would delete exactly what it asserts.
 */
public abstract class PostgresBackedTest {

    /**
     * Resolved once per JVM. The table list is a property of the migrated schema, not of a test,
     * and {@code information_schema} is not free.
     */
    private static volatile String truncateStatement;

    @Autowired
    private JdbcTemplate postgresBackedTestJdbcTemplate;

    @BeforeEach
    void truncateEveryApplicationTable() {
        String statement = truncateStatement;
        if (statement == null) {
            statement = buildTruncateStatement();
            truncateStatement = statement;
        }
        postgresBackedTestJdbcTemplate.execute(statement);
    }

    private String buildTruncateStatement() {
        List<String> tables = postgresBackedTestJdbcTemplate.queryForList(
                """
                SELECT quote_ident(table_name)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """,
                String.class);
        if (tables.isEmpty()) {
            throw new IllegalStateException(
                    "No application tables found in schema 'public' — the migrations did not run.");
        }
        return "TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE";
    }
}
