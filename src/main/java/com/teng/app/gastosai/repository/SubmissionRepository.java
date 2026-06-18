package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.Submission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {
	List<Submission> findAllByOrderByCreatedAtDesc();
}
