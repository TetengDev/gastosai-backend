package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

	List<Expense> findAllByUser(User user);

	Optional<Expense> findByIdAndUser(Long id, User user);

	boolean existsByIdAndUser(Long id, User user);

	@Modifying
	@Query("DELETE FROM Expense e WHERE e.user = :user")
	void deleteAllByUser(@Param("user") User user);

	@Query("""
			SELECT YEAR(e.date), MONTH(e.date), COALESCE(SUM(e.amount), 0)
			FROM Expense e
			WHERE e.date IS NOT NULL AND e.user = :user
			GROUP BY YEAR(e.date), MONTH(e.date)
			ORDER BY YEAR(e.date), MONTH(e.date)
			""")
	List<Object[]> sumByYearMonth(@Param("user") User user);

	@Query("""
			SELECT COALESCE(c.name, 'Uncategorized'), COALESCE(SUM(e.amount), 0)
			FROM Expense e
			LEFT JOIN e.category c
			WHERE e.user = :user
			GROUP BY c.name
			ORDER BY SUM(e.amount) DESC
			""")
	List<Object[]> sumByCategory(@Param("user") User user);

	long countByCategory_Id(Long categoryId);

	List<Expense> findByCategory_Id(Long categoryId);
}
