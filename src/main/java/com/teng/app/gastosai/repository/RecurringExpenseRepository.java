package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.Frequency;
import com.teng.app.gastosai.entity.RecurringExpense;
import com.teng.app.gastosai.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RecurringExpenseRepository extends JpaRepository<RecurringExpense, Long> {

	List<RecurringExpense> findAllByUser(User user);

	List<RecurringExpense> findAllByUserAndActiveTrue(User user);

	Optional<RecurringExpense> findByIdAndUser(Long id, User user);

	boolean existsByIdAndUser(Long id, User user);

	boolean existsByUserAndNameIgnoreCaseAndFrequency(User user, String name, Frequency frequency);

	// --- Paid months (recurring_expense_payments, V33) ---
	//
	// Native SQL over a table with no entity: nothing reads a payment row as an object, only as
	// "is (bill, month) present". ON CONFLICT also has no JPQL form, and it is what makes a repeated
	// mark idempotent without a check-then-insert race.

	/** Records {@code month} as paid; a second call for the same month changes nothing. */
	@Modifying
	@Query(value = "INSERT INTO recurring_expense_payments (recurring_expense_id, payment_month, paid_at) "
			+ "VALUES (:id, :month, :paidAt) ON CONFLICT (recurring_expense_id, payment_month) DO NOTHING",
			nativeQuery = true)
	void insertPaymentIfAbsent(@Param("id") long recurringExpenseId, @Param("month") String month,
			@Param("paidAt") LocalDateTime paidAt);

	@Modifying
	@Query(value = "DELETE FROM recurring_expense_payments "
			+ "WHERE recurring_expense_id = :id AND payment_month = :month", nativeQuery = true)
	void deletePayment(@Param("id") long recurringExpenseId, @Param("month") String month);

	/** One query for the whole list: the ids of the user's recurring expenses paid for {@code month}. */
	@Query(value = "SELECT p.recurring_expense_id FROM recurring_expense_payments p "
			+ "JOIN recurring_expenses r ON r.id = p.recurring_expense_id "
			+ "WHERE r.user_id = :userId AND p.payment_month = :month", nativeQuery = true)
	List<Long> findPaidRecurringExpenseIds(@Param("userId") long userId, @Param("month") String month);
}
