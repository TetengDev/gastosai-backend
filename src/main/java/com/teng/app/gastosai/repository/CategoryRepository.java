package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

	Optional<Category> findByUserAndNameIgnoreCase(User user, String name);

	boolean existsByUserAndNameIgnoreCase(User user, String name);

	List<Category> findAllByUser(User user);

	long countByUser(User user);

	Optional<Category> findByIdAndUser(Long id, User user);
}
