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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_checkout")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentCheckout {

    /**
     * How long a checkout is allowed to stay unresolved before it counts as abandoned.
     *
     * <p>Matched to the provider's own checkout-session lifetime: past this point the payment page
     * the user was sent to no longer accepts a payment, so a row still {@code PENDING} here is not
     * waiting for anything — it is a page that was closed. Twenty-four hours is deliberately
     * generous, because the cost of expiring early (telling a user their live checkout is dead) is
     * worse than the cost of expiring late (one stale row nobody is looking at).
     *
     * <p>A constant rather than a setting: it is a fact about the provider, not a knob an operator
     * should be turning, and a value that changed between two deployments would make already-stored
     * rows expire at a time their own {@code created_at} does not explain.
     */
    public static final Duration WINDOW = Duration.ofHours(24);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "session_id", nullable = false, unique = true, length = 200)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_key", nullable = false, length = 20)
    private PlanKey planKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", nullable = false, length = 20)
    private BillingPeriod billingPeriod;

    @Column(name = "amount_centavos", nullable = false)
    private int amountCentavos;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CheckoutStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /**
     * Stamps the creation time, unless the caller already chose one.
     *
     * <p>It used to overwrite unconditionally, which quietly discarded the {@code createdAt} the
     * builder had been given — and since the whole of expiry is derived from that field, a checkout
     * could not be stored as anything but brand new. Honouring an explicit value is what lets an
     * aged checkout exist at all.
     */
    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    // -------------------------------------------------------------------------------------------
    // Lifecycle (TEN-149)
    //
    // Every status change goes through one of the three methods below, and each answers whether it
    // changed anything rather than throwing. A webhook is the main caller, and a delivery that
    // raises on an out-of-order event is a delivery the provider retries forever.
    //
    //   from      markPaid          markFailed        expireIfElapsed
    //   PENDING   -> PAID           -> FAILED         -> EXPIRED once the window has elapsed
    //   PAID      no change         no change         no change
    //   EXPIRED   -> PAID           no change         no change
    //   FAILED    -> PAID           no change         no change
    //
    // PAID is reachable from the two resolved-but-unpaid statuses on purpose: those two are this
    // service's reading of a silence, and a payment the provider confirms is not a silence. Money
    // moved, so the user gets what they paid for even if we had already given up on the checkout.
    // Nothing is reachable out of PAID, which is the direction that would cost someone access.
    // -------------------------------------------------------------------------------------------

    /** When this checkout stops being startable, or {@code null} before it has been persisted. */
    public LocalDateTime expiresAt() {
        return createdAt == null ? null : createdAt.plus(WINDOW);
    }

    /**
     * Whether the window has run out as of {@code now}. A checkout with no creation time yet —
     * one that has never been saved — has not elapsed.
     */
    public boolean hasElapsed(LocalDateTime now) {
        LocalDateTime expiresAt = expiresAt();
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /**
     * The status as of {@code now}, with an elapsed window taken into account.
     *
     * <p>Expiry is a fact about the clock, not an event anyone delivers, so a stored {@code PENDING}
     * whose window has run out is already abandoned whether or not anything has got around to
     * writing that down. This is the reading callers should show and act on;
     * {@link #expireIfElapsed} is what makes the stored row agree.
     */
    public CheckoutStatus effectiveStatus(LocalDateTime now) {
        return status == CheckoutStatus.PENDING && hasElapsed(now) ? CheckoutStatus.EXPIRED : status;
    }

    /**
     * Records that the provider settled this checkout.
     *
     * @return {@code false} if it was already {@code PAID} — a replayed delivery, which must not
     *         move {@code paidAt}
     */
    public boolean markPaid(LocalDateTime paidAt) {
        if (status == CheckoutStatus.PAID) {
            return false;
        }
        status = CheckoutStatus.PAID;
        this.paidAt = paidAt;
        return true;
    }

    /**
     * Records that the provider tried this payment and refused it.
     *
     * @return {@code false} unless the checkout was still {@code PENDING}; a failure never
     *         overrides an outcome the checkout already has, least of all {@code PAID}
     */
    public boolean markFailed() {
        if (status != CheckoutStatus.PENDING) {
            return false;
        }
        status = CheckoutStatus.FAILED;
        return true;
    }

    /**
     * Writes down an expiry the clock has already decided.
     *
     * @return {@code true} only when this call changed {@code PENDING} into {@code EXPIRED}, so a
     *         caller can save exactly the rows that moved
     */
    public boolean expireIfElapsed(LocalDateTime now) {
        if (status != CheckoutStatus.PENDING || !hasElapsed(now)) {
            return false;
        }
        status = CheckoutStatus.EXPIRED;
        return true;
    }
}
