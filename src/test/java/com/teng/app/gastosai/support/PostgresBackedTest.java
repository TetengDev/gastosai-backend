package com.teng.app.gastosai.support;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.teng.app.gastosai.bootstrap.EntitlementSeeder;
import com.teng.app.gastosai.config.AiStartupValidator;

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
 * ordering — {@code CASCADE} follows the foreign keys.
 *
 * <p>{@code flyway_schema_history} is excluded on purpose: truncating it would make Flyway
 * re-apply the whole chain on the next context refresh against a schema that already exists.
 *
 * <p>Identity sequences are deliberately <em>not</em> restarted. The in-memory rate limiter keys its
 * fixed window on the user id ({@code "write:" + user.getId()}), and that store is a singleton in a
 * Spring context the test-context cache shares across many classes. Restarting identity would hand
 * every class the same ids, so their windows would collide and the tenth class to write would get a
 * 429 for the first request it made. Letting the sequences run on keeps ids unique for the life of
 * the JVM, which is what the per-context H2 database used to give for free.
 *
 * <p>The reset runs in its <em>own committed transaction</em>, never the test's. {@code TRUNCATE}
 * takes an {@code ACCESS EXCLUSIVE} lock on every table it names, and joining a {@code @Transactional}
 * test's transaction would hold those locks for the whole test body. That is not a slow path, it is
 * a hang: {@code AiUsageService.record} and {@code AppEventService} are
 * {@code Propagation.REQUIRES_NEW}, so they take a <em>second</em> connection and try to insert into
 * {@code ai_usage} / the event table while the test's own outer transaction still holds the exclusive
 * lock on it. A transaction cannot wait for itself, so the suite stalls forever rather than failing.
 * Committing the reset before the test body releases the locks up front and the nesting works
 * normally. Under H2 each context had its own database, so this contention could not arise; one
 * shared container makes it structural.
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

    /**
     * The startup guard that refuses to boot when managed AI mode is on without a shared key. It
     * skipped itself in tests by recognising a {@code jdbc:h2:} datasource URL, and a
     * Testcontainers {@code jdbc:tc:} URL is not one — so on this profile it starts firing, and
     * every context carrying {@code gastos.ai.allow-shared-key=true} fails to start.
     *
     * <p>The alternative is to configure a non-blank {@code gastos.openai.api-key} for the suite,
     * and that is worse: the OpenAI client's base URL is hard-coded to {@code api.openai.com}, so a
     * key that looks real turns any test that does not mock the provider into a live call. Mocking
     * the validator keeps the guard from firing without arming the client, and loses no coverage —
     * the validator's own rules are asserted directly in {@code AiStartupValidatorTest}, and under
     * H2 this listener never ran in a test context either.
     *
     * <p>The datasource heuristic itself is wrong now and belongs in {@code src/main}, which is
     * outside this issue.
     */
    @MockitoBean
    private AiStartupValidator aiStartupValidator;

    @Autowired
    private JdbcTemplate postgresBackedTestJdbcTemplate;

    /**
     * The plans and feature grants in {@code subscription_plans} / {@code plan_features} are
     * reference data, not test data: a {@code CommandLineRunner} seeds them once when the context
     * starts, and everything entitlement-shaped — pricing, checkout, quota, the feature gates —
     * reads them. Truncating them leaves nothing to put them back, because the runner has already
     * run and will not run again for a cached context.
     *
     * <p>Re-seeding is preferred to excluding the two tables from the {@code TRUNCATE}: the seeder
     * is idempotent and is the definition of what a freshly started context holds, so calling it
     * keeps the reset honest even if a test writes to those tables.
     */
    @Autowired
    private EntitlementSeeder entitlementSeeder;

    @Autowired
    private PlatformTransactionManager postgresBackedTestTransactionManager;

    @BeforeEach
    void resetToAFreshlyStartedDatabase() {
        String statement = truncateStatement;
        if (statement == null) {
            statement = buildTruncateStatement();
            truncateStatement = statement;
        }
        String truncate = statement;

        // REQUIRES_NEW, not the ambient transaction: see the class javadoc. Spring has already
        // started the test's transaction by the time @BeforeEach runs, so without this the
        // TRUNCATE would enlist in it and hold ACCESS EXCLUSIVE locks for the whole test.
        TransactionTemplate ownTransaction = new TransactionTemplate(postgresBackedTestTransactionManager);
        ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ownTransaction.executeWithoutResult(status -> {
            postgresBackedTestJdbcTemplate.execute(truncate);
            entitlementSeeder.run();
        });
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
        return "TRUNCATE TABLE " + String.join(", ", tables) + " CASCADE";
    }
}
