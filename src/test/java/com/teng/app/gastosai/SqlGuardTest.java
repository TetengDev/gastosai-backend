package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.SqlGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlGuardTest {

    @Test
    void rejectsInsert() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.validateAndNormalize("INSERT INTO expenses VALUES (1)"));
    }

    @Test
    void rejectsUpdate() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.validateAndNormalize("UPDATE expenses SET amount = 0"));
    }

    @Test
    void rejectsDelete() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.validateAndNormalize("DELETE FROM expenses WHERE id = 1"));
    }

    @Test
    void rejectsDropTable() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.validateAndNormalize("DROP TABLE expenses"));
    }

    @Test
    void rejectsTruncate() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.validateAndNormalize("TRUNCATE expenses"));
    }

    @Test
    void rejectsSelectWithoutFromExpenses_selectOne() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.validateAndNormalize("SELECT 1"));
    }

    @Test
    void rejectsSelectWithoutFromExpenses_otherTable() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.validateAndNormalize("SELECT * FROM users"));
    }

    @Test
    void rejectsMultiStatement() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.validateAndNormalize("SELECT * FROM expenses; DROP TABLE expenses"));
    }

    @Test
    void rejectsPgTables() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.validateAndNormalize("SELECT * FROM pg_tables"));
    }

    @Test
    void rejectsInformationSchema() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.validateAndNormalize("SELECT * FROM information_schema.tables"));
    }

    @Test
    void acceptsValidSelectFromExpenses() {
        assertDoesNotThrow(() ->
                SqlGuard.validateAndNormalize("SELECT * FROM expenses WHERE user_id = 1"));
    }

    @Test
    void acceptsValidSelectWithAlias() {
        assertDoesNotThrow(() ->
                SqlGuard.validateAndNormalize("SELECT e.amount FROM expenses e WHERE e.id = 1"));
    }

    @Test
    void rejectsNullSql() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.validateAndNormalize(null));
    }

    @Test
    void rejectsBlankSql() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.validateAndNormalize("   "));
    }
}
