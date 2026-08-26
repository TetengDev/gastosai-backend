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
import lombok.AllArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "expenses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Expense {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, precision = 19, scale = 4)
	private BigDecimal amount;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private User user;

	@ManyToOne
	@JoinColumn(name = "category_id")
	@OnDelete(action = OnDeleteAction.CASCADE)
	private Category category;

	/**
	 * The project or client this expense is billable to, or null when it is not billable to one.
	 *
	 * <p>Nullable by design — most expenses carry no tag, and an untagged expense must stay a
	 * first-class row rather than being forced into a catch-all tag. Deleting a tag detaches the
	 * expenses instead of taking them with it (ON DELETE SET NULL in V28); losing an expense
	 * because a tag was tidied away would be the worse failure by far.
	 *
	 * <p>Fetched the same way {@code category} is, and deliberately not {@code LAZY}: every list,
	 * page and report path reads the tag's name to build the response, so a lazy association would
	 * buy nothing and cost a per-row select on exactly the hot paths — including the specification
	 * query, where a declared {@code @EntityGraph} would not reach.
	 */
	@ManyToOne
	@JoinColumn(name = "project_id")
	@OnDelete(action = OnDeleteAction.SET_NULL)
	private Project project;

	private LocalDateTime date;

	@Column(nullable = false, columnDefinition = "text")
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	@Builder.Default
	private ExpenseType expenseType = ExpenseType.PERSONAL;

	@Column(nullable = false)
	@Builder.Default
	private boolean reimbursable = false;

	@Column(nullable = false, length = 3)
	@Builder.Default
	private String currency = "PHP";

	@Column(nullable = false, precision = 19, scale = 6)
	@Builder.Default
	private BigDecimal exchangeRate = BigDecimal.ONE;

	@Column(nullable = false, precision = 19, scale = 4)
	private BigDecimal amountInBaseCurrency;

	/**
	 * True when a human set this expense's category by hand against a merchant rule that said
	 * something else. It records the disagreement on the one row instead of rewriting the rule, so
	 * the rule keeps categorising every later expense from that merchant.
	 */
	@Column(name = "category_overridden", nullable = false)
	@Builder.Default
	private boolean categoryOverridden = false;

	/**
	 * Which route created this expense. Set once, at creation; {@code ExpenseService#update} leaves
	 * it alone on purpose — see {@link ExpenseSource}.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "source", nullable = false, length = 20)
	@Builder.Default
	private ExpenseSource source = ExpenseSource.MANUAL;
}
