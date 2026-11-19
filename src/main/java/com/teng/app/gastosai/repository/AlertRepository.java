package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.Alert;
import com.teng.app.gastosai.entity.AlertType;
import com.teng.app.gastosai.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findAllByUserAndMonthAndDismissedFalseOrderBySeverityDescCreatedAtDesc(User user, String month);

    Optional<Alert> findByUserAndTypeAndMonthAndCategoryName(User user, AlertType type, String month, String categoryName);

    Optional<Alert> findByIdAndUser(Long id, User user);

    Optional<Alert> findByUserAndTypeAndMonthAndRecurringExpenseId(User user, AlertType type, String month, Long recurringExpenseId);

    /**
     * Takes a transaction-scoped Postgres advisory lock on one user's alerts, held until the
     * caller's transaction ends. Alert generation is look-up-then-insert, so two overlapping
     * generation passes for the same user must run one after the other: the second then finds the
     * rows the first committed instead of inserting them again (TEN-551). The key is namespaced by
     * hashing, so it cannot collide with a bare numeric advisory lock taken elsewhere; a hash
     * collision between two users only makes them wait for each other, briefly.
     *
     * <p>Wrapped in {@code count(*)} because {@code pg_advisory_xact_lock} returns {@code void},
     * which has no JDBC mapping.
     */
    @Query(value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(hashtextextended('alerts:' || :userId, 0))) l",
            nativeQuery = true)
    long lockAlertsOf(@Param("userId") Long userId);
}
