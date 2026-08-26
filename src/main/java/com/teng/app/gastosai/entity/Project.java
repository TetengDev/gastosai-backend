package com.teng.app.gastosai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * A billing tag: the project or client an expense is attributed to.
 *
 * <p>One entity covers both words in "project or client" on purpose. A freelancer tags with
 * whatever they bill against — "Acme Corp" for a client, "Acme redesign" for an engagement within
 * it — and the two behave identically everywhere: they filter the same way, total the same way and
 * rename the same way. Modelling them as separate concepts would ask the user to classify their
 * own tag before they could use it, and would double every query below for no gain.
 *
 * <p>Tags are per user, like {@link Category}. An expense's tag is optional; most are untagged.
 *
 * <p>The name is stored here and nowhere else, and an expense points at the row by id. That is what
 * makes a rename a one-row update that leaves every tagged expense attributed exactly as it was —
 * had the tag been a string copied onto each expense, renaming would either orphan the history or
 * need a sweep over it.
 */
@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 60)
	private String name;

	@ManyToOne(optional = false, fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private User user;
}
