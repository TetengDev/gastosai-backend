package com.teng.app.gastosai;

import com.teng.app.gastosai.entity.BillingPeriod;
import com.teng.app.gastosai.entity.CheckoutStatus;
import com.teng.app.gastosai.entity.PaymentCheckout;
import com.teng.app.gastosai.entity.PlanKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checkout state machine (TEN-149), covered exhaustively: every one of the four statuses is put
 * through every one of the three transitions, so a status added later without a rule for it fails
 * here rather than acquiring one by accident.
 *
 * <p>No Spring context — this is domain behaviour on an entity, and the transitions are the part
 * worth pinning. {@code PaymentCheckoutLifecycleTest} covers the same rules as they are reached
 * through the service and the database.
 */
class PaymentCheckoutTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 8, 20, 9, 0);
    private static final LocalDateTime WITHIN_WINDOW = CREATED.plusHours(23);
    private static final LocalDateTime PAST_WINDOW = CREATED.plusHours(25);

    // -------------------------------------------------------------------------------------------
    // The window
    // -------------------------------------------------------------------------------------------

    @Test
    void expiryIsTwentyFourHoursAfterCreation() {
        assertThat(PaymentCheckout.WINDOW.toHours()).isEqualTo(24);
        assertThat(checkout(CheckoutStatus.PENDING).expiresAt()).isEqualTo(CREATED.plusHours(24));
    }

    @Test
    void theWindowIsClosedAtTheInstantItElapses() {
        PaymentCheckout checkout = checkout(CheckoutStatus.PENDING);

        assertThat(checkout.hasElapsed(CREATED.plusHours(24).minusNanos(1))).isFalse();
        assertThat(checkout.hasElapsed(CREATED.plusHours(24))).isTrue();
    }

    @Test
    void anUnsavedCheckoutHasNoWindowAndCannotElapse() {
        PaymentCheckout unsaved = PaymentCheckout.builder()
                .sessionId("cs_unsaved")
                .planKey(PlanKey.PREMIUM)
                .billingPeriod(BillingPeriod.MONTHLY)
                .amountCentavos(14900)
                .status(CheckoutStatus.PENDING)
                .build();

        assertThat(unsaved.expiresAt()).isNull();
        assertThat(unsaved.hasElapsed(PAST_WINDOW)).isFalse();
        assertThat(unsaved.effectiveStatus(PAST_WINDOW)).isEqualTo(CheckoutStatus.PENDING);
        assertThat(unsaved.expireIfElapsed(PAST_WINDOW)).isFalse();
    }

    // -------------------------------------------------------------------------------------------
    // effectiveStatus: expiry is read from the clock, not waited for
    // -------------------------------------------------------------------------------------------

    @Test
    void anElapsedPendingCheckoutReadsAsExpiredBeforeAnythingWritesItDown() {
        PaymentCheckout checkout = checkout(CheckoutStatus.PENDING);

        assertThat(checkout.effectiveStatus(WITHIN_WINDOW)).isEqualTo(CheckoutStatus.PENDING);
        assertThat(checkout.effectiveStatus(PAST_WINDOW)).isEqualTo(CheckoutStatus.EXPIRED);
        assertThat(checkout.getStatus()).isEqualTo(CheckoutStatus.PENDING);
    }

    @ParameterizedTest
    @EnumSource(value = CheckoutStatus.class, names = {"PAID", "EXPIRED", "FAILED"})
    void aResolvedCheckoutIsUnaffectedByTheWindow(CheckoutStatus resolved) {
        PaymentCheckout checkout = checkout(resolved);

        assertThat(checkout.effectiveStatus(WITHIN_WINDOW)).isEqualTo(resolved);
        assertThat(checkout.effectiveStatus(PAST_WINDOW)).isEqualTo(resolved);
    }

    // -------------------------------------------------------------------------------------------
    // markPaid — from every status
    // -------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = CheckoutStatus.class, names = {"PENDING", "EXPIRED", "FAILED"})
    void aSettledPaymentWinsOverEveryUnpaidStatus(CheckoutStatus before) {
        PaymentCheckout checkout = checkout(before);

        assertThat(checkout.markPaid(PAST_WINDOW)).isTrue();
        assertThat(checkout.getStatus()).isEqualTo(CheckoutStatus.PAID);
        assertThat(checkout.getPaidAt()).isEqualTo(PAST_WINDOW);
    }

    @Test
    void markPaidOnAnAlreadyPaidCheckoutChangesNothing() {
        PaymentCheckout checkout = checkout(CheckoutStatus.PAID);
        checkout.setPaidAt(WITHIN_WINDOW);

        assertThat(checkout.markPaid(PAST_WINDOW)).isFalse();
        assertThat(checkout.getStatus()).isEqualTo(CheckoutStatus.PAID);
        assertThat(checkout.getPaidAt()).isEqualTo(WITHIN_WINDOW);
    }

    // -------------------------------------------------------------------------------------------
    // markFailed — from every status
    // -------------------------------------------------------------------------------------------

    @Test
    void aRefusedPaymentFailsAPendingCheckout() {
        PaymentCheckout checkout = checkout(CheckoutStatus.PENDING);

        assertThat(checkout.markFailed()).isTrue();
        assertThat(checkout.getStatus()).isEqualTo(CheckoutStatus.FAILED);
        assertThat(checkout.getPaidAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = CheckoutStatus.class, names = {"PAID", "EXPIRED", "FAILED"})
    void aRefusedPaymentNeverOverridesAResolvedCheckout(CheckoutStatus resolved) {
        PaymentCheckout checkout = checkout(resolved);

        assertThat(checkout.markFailed()).isFalse();
        assertThat(checkout.getStatus()).isEqualTo(resolved);
    }

    // -------------------------------------------------------------------------------------------
    // expireIfElapsed — from every status
    // -------------------------------------------------------------------------------------------

    @Test
    void expireIfElapsedWritesDownAnElapsedPendingCheckout() {
        PaymentCheckout checkout = checkout(CheckoutStatus.PENDING);

        assertThat(checkout.expireIfElapsed(PAST_WINDOW)).isTrue();
        assertThat(checkout.getStatus()).isEqualTo(CheckoutStatus.EXPIRED);
        assertThat(checkout.getPaidAt()).isNull();
    }

    @Test
    void expireIfElapsedLeavesALiveCheckoutAlone() {
        PaymentCheckout checkout = checkout(CheckoutStatus.PENDING);

        assertThat(checkout.expireIfElapsed(WITHIN_WINDOW)).isFalse();
        assertThat(checkout.getStatus()).isEqualTo(CheckoutStatus.PENDING);
    }

    @ParameterizedTest
    @EnumSource(value = CheckoutStatus.class, names = {"PAID", "EXPIRED", "FAILED"})
    void expireIfElapsedNeverTouchesAResolvedCheckout(CheckoutStatus resolved) {
        PaymentCheckout checkout = checkout(resolved);

        assertThat(checkout.expireIfElapsed(PAST_WINDOW)).isFalse();
        assertThat(checkout.getStatus()).isEqualTo(resolved);
    }

    /**
     * The failure that would cost a customer their access: no sequence of transitions, in any
     * order, may move a checkout out of {@code PAID}.
     */
    @Test
    void nothingMovesACheckoutOutOfPaid() {
        PaymentCheckout checkout = checkout(CheckoutStatus.PAID);
        checkout.setPaidAt(WITHIN_WINDOW);

        checkout.markFailed();
        checkout.expireIfElapsed(PAST_WINDOW);
        checkout.markFailed();
        checkout.markPaid(PAST_WINDOW);

        assertThat(checkout.getStatus()).isEqualTo(CheckoutStatus.PAID);
        assertThat(checkout.effectiveStatus(PAST_WINDOW)).isEqualTo(CheckoutStatus.PAID);
        assertThat(checkout.getPaidAt()).isEqualTo(WITHIN_WINDOW);
    }

    // -------------------------------------------------------------------------------------------
    // The status vocabulary itself
    // -------------------------------------------------------------------------------------------

    @Test
    void pendingIsTheOnlyUnresolvedStatusAndPaidTheOnlySettledOne() {
        assertThat(EnumSet.allOf(CheckoutStatus.class))
                .containsExactlyInAnyOrder(CheckoutStatus.PENDING, CheckoutStatus.PAID,
                        CheckoutStatus.EXPIRED, CheckoutStatus.FAILED);

        for (CheckoutStatus status : CheckoutStatus.values()) {
            assertThat(status.isResolved()).isEqualTo(status != CheckoutStatus.PENDING);
            assertThat(status.isSettled()).isEqualTo(status == CheckoutStatus.PAID);
        }
    }

    /** Abandonment and failure must be tellable apart — the second acceptance criterion, directly. */
    @Test
    void abandonmentAndFailureAreDistinctResolutions() {
        PaymentCheckout abandoned = checkout(CheckoutStatus.PENDING);
        PaymentCheckout refused = checkout(CheckoutStatus.PENDING);

        abandoned.expireIfElapsed(PAST_WINDOW);
        refused.markFailed();

        assertThat(abandoned.getStatus()).isNotEqualTo(refused.getStatus());
        assertThat(abandoned.getStatus()).isEqualTo(CheckoutStatus.EXPIRED);
        assertThat(refused.getStatus()).isEqualTo(CheckoutStatus.FAILED);
        assertThat(abandoned.getStatus().isResolved()).isTrue();
        assertThat(refused.getStatus().isResolved()).isTrue();
    }

    private static PaymentCheckout checkout(CheckoutStatus status) {
        return PaymentCheckout.builder()
                .sessionId("cs_" + status)
                .planKey(PlanKey.PREMIUM)
                .billingPeriod(BillingPeriod.MONTHLY)
                .amountCentavos(14900)
                .status(status)
                .createdAt(CREATED)
                .build();
    }
}
