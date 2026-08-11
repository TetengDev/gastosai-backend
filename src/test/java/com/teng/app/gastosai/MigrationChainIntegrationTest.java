package com.teng.app.gastosai;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Applies the whole Flyway chain to an empty, real PostgreSQL and then lets Hibernate validate
 * the entities against the resulting schema.
 *
 * <p>The rest of the suite is on real PostgreSQL too (TEN-241), sharing one container across every
 * Spring context. This class is the only one that does not join it, and the reason is the whole
 * point of the test: that container is migrated once by whichever context starts first and then
 * accumulates rows for the rest of the run, so it can never again show what a <em>new</em>
 * environment sees. This one is empty on every run (a fresh volume per container), so Flyway
 * starts from nothing and applies V1..Vn in order — the same path a new environment takes. Two
 * containers per suite is the price of that guarantee, and it is the reason the class keeps its
 * own {@code testcontainers} profile rather than the default one.
 *
 * <p>PostgreSQL 17 in both places: the version in docker-compose.yaml and in Supabase.
 *
 * <p>Skipped, not failed, when no Docker daemon is reachable, so a local run without Docker still
 * gets the rest of the suite. CI runners have Docker, so the gate holds where it matters.
 */
@SpringBootTest
@ActiveProfiles("testcontainers")
@Testcontainers(disabledWithoutDocker = true)
class MigrationChainIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired Environment environment;

    @Test
    void everyMigrationAppliesCleanlyToAnEmptyDatabase() throws IOException {
        List<MigrationInfo> applied = Arrays.asList(flyway.info().applied());

        assertThat(applied)
                .allSatisfy(info -> assertThat(info.getState())
                        .as("migration %s (%s) state", info.getVersion(), info.getDescription())
                        .isEqualTo(MigrationState.SUCCESS));

        // Counted against the scripts on the classpath rather than a literal, so adding a
        // migration does not require editing this test. Repeatable migrations (R__) are counted
        // separately because they are not part of the versioned chain — there are none today.
        List<MigrationInfo> versioned = applied.stream().filter(i -> i.getVersion() != null).toList();
        assertThat(versioned)
                .as("every versioned migration on the classpath must have been applied")
                .hasSize(migrationScriptCount());

        // Flyway refuses to hand out a "current" version if the chain is broken or pending, so
        // this also pins that the head the entities were validated against is the last script.
        assertThat(flyway.info().current().getState()).isEqualTo(MigrationState.SUCCESS);
    }

    @Test
    void schemaIsOwnedByFlywayAndValidatesAgainstTheEntities() {
        // Reaching this point at all means ddl-auto=validate passed during context startup: with
        // the testcontainers profile Hibernate creates nothing, so any column the entities expect
        // and the migrations did not create would have failed the context before the first test.
        // That is only true while the profile keeps Flyway on and Hibernate in validate mode —
        // flip either and this class would still go green while proving nothing, so pin both.
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                .as("Hibernate must validate the migrated schema, never generate one")
                .isEqualTo("validate");
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class))
                .as("the schema under test must come from the migrations")
                .isTrue();

        Integer historyRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        assertThat(historyRows).as("failed rows in flyway_schema_history").isZero();

        // A sanity check that the schema really came from the migrations rather than an empty
        // database plus a lenient validator: a table only the migrations create must exist.
        Integer expenses = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'expenses'",
                Integer.class);
        assertThat(expenses).as("expenses table created by the migration chain").isEqualTo(1);
    }

    private int migrationScriptCount() throws IOException {
        Resource[] scripts = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V*__*.sql");
        assertThat(scripts).as("migration scripts on the classpath").isNotEmpty();
        return scripts.length;
    }
}
