package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.Budget;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BudgetRepository extends JpaRepository<Budget, Long> {

	List<Budget> findAllByUserAndMonth(User user, String month);

	Optional<Budget> findByIdAndUser(Long id, User user);

	Optional<Budget> findByUserAndCategoryAndMonth(User user, Category category, String month);
}
