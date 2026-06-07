package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

	@Query("""
			SELECT YEAR(e.date), MONTH(e.date), COALESCE(SUM(e.amount), 0)
			FROM Expense e
			WHERE e.date IS NOT NULL
			GROUP BY YEAR(e.date), MONTH(e.date)
			ORDER BY YEAR(e.date), MONTH(e.date)
			""")
	List<Object[]> sumByYearMonth();

	@Query("""
			SELECT COALESCE(c.name, 'Uncategorized'), COALESCE(SUM(e.amount), 0)
			FROM Expense e
			LEFT JOIN e.category c
			GROUP BY c.name
			ORDER BY SUM(e.amount) DESC
			""")
	List<Object[]> sumByCategory();

	long countByCategory_Id(Long categoryId);
}
