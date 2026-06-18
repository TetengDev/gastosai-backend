package com.teng.app.gastosai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** A message submitted from the public Contact / Feedback pages. */
@Entity
@Table(name = "submissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Submission {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SubmissionType type;

	@Column(length = 100)
	private String name;

	@Column(length = 200)
	private String email;

	@Column(nullable = false, length = 2000)
	private String message;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(nullable = false)
	@Builder.Default
	private boolean handled = false;

	@PrePersist
	void onCreate() {
		createdAt = LocalDateTime.now();
	}
}
