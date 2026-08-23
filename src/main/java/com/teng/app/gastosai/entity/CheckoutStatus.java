package com.teng.app.gastosai.entity;

/**
 * Where one checkout ended up.
 *
 * <p>Three of these are outcomes and one is a wait. {@link #PENDING} is the only status that means
 * "still in progress"; every other value says the checkout is over and the user is free to start
 * another. Telling the endings apart matters because they need different answers: {@link #EXPIRED}
 * is a page the user closed and can simply retry, while {@link #FAILED} is a payment the provider
 * actively refused and usually needs a different card.
 *
 * <p>Stored as {@code VARCHAR(20)} with no check constraint (see {@code V19__payment_checkout.sql}),
 * so a new value here needs no migration. A value must never be renamed, though — the persisted rows
 * carry the name itself.
 */
public enum CheckoutStatus {

    /** Created here, not yet resolved by the provider. The only non-final status. */
    PENDING,

    /** The provider settled the payment. The one status that money has actually moved behind. */
    PAID,

    /** Abandoned: its window elapsed with no outcome from the provider. See {@link PaymentCheckout#WINDOW}. */
    EXPIRED,

    /** The provider tried the payment and refused it — declined card, insufficient funds, and so on. */
    FAILED;

    /**
     * Whether this checkout is over, whatever its outcome. A user with no {@code PENDING} checkout
     * is not waiting on anything, which is what makes a new checkout safe to start.
     */
    public boolean isResolved() {
        return this != PENDING;
    }

    /** Whether this status means the payment succeeded. Only {@link #PAID} does. */
    public boolean isSettled() {
        return this == PAID;
    }
}
