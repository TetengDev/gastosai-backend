package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.RecurringExpense;
import com.teng.app.gastosai.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecurringExpenseRepository extends JpaRepository<RecurringExpense, Long> {

	List<RecurringExpense> findAllByUser(User user);

	List<RecurringExpense> findAllByUserAndActiveTrue(User user);

	Optional<RecurringExpense> findByIdAndUser(Long id, User user);

	boolean existsByIdAndUser(Long id, User user);
}
