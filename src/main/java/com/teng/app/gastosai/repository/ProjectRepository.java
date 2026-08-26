package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.Project;
import com.teng.app.gastosai.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

	/** Case-insensitive, so "acme" and "Acme" are one tag rather than two. Mirrors categories. */
	Optional<Project> findByUserAndNameIgnoreCase(User user, String name);

	Optional<Project> findByIdAndUser(Long id, User user);

	List<Project> findAllByUserOrderByNameAsc(User user);
}
