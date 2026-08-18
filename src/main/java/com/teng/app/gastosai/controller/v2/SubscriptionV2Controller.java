package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.controller.SubscriptionController;
import com.teng.app.gastosai.dto.CheckoutRequest;
import com.teng.app.gastosai.dto.CheckoutResponse;
import com.teng.app.gastosai.dto.PricingItem;
import com.teng.app.gastosai.dto.SubscriptionResponse;
import com.teng.app.gastosai.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@link SubscriptionController} on the v2 path.
 *
 * <p>{@link PricingItem} is unchanged, and already correct: it has always served
 * {@code amountCentavos} as an integer. Plan prices came from PayMongo, which is centavo-native,
 * so this is the one money field that never needed the v2 treatment.
 */
@RestController
@RequestMapping("/api/v2/subscription")
@RequiredArgsConstructor
public class SubscriptionV2Controller {

	private final SubscriptionController delegate;

	@PostMapping("/checkout")
	@Operation(operationId = "v2StartCheckout")
	public CheckoutResponse startCheckout(@Valid @RequestBody CheckoutRequest request,
			@AuthenticationPrincipal User user) {
		return delegate.startCheckout(request, user);
	}

	@GetMapping
	@Operation(operationId = "v2CurrentSubscription")
	public SubscriptionResponse current(@AuthenticationPrincipal User user) {
		return delegate.current(user);
	}

	@GetMapping("/pricing")
	@Operation(operationId = "v2Pricing")
	public List<PricingItem> pricing() {
		return delegate.pricing();
	}
}
