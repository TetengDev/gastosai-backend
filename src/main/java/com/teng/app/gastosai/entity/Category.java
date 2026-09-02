package com.teng.app.gastosai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50)
	private String name;

	@Column(length = 50)
	private String icon;

	/**
	 * True for a category the system provided rather than the user creating: the starter set
	 * {@code CategorySeedService} writes at registration, and the {@code Uncategorized} fallback.
	 *
	 * <p>System-provided rows do not consume the plan's category cap (TEN-327) — a FREE account
	 * keeps all 13 starters and may still create 5 of its own. The value is set once, when the row
	 * is created, and never inferred from the name afterwards: a user may rename or delete a
	 * starter, and may create a category whose name matches one, and neither may change how the row
	 * counts. It is not writable through any endpoint, so a user cannot buy themselves headroom by
	 * flagging their own rows.
	 */
	@Column(name = "system_provided", nullable = false)
	private boolean systemProvided;

	/** Optional bucket for rule-based budgeting (NEEDS/WANTS/SAVINGS); null = unassigned. */
	@Enumerated(EnumType.STRING)
	@Column(length = 16)
	private Bucket bucket;

	@ManyToOne(optional = false, fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private User user;
}
