package com.teng.app.gastosai.controller;

import com.teng.app.gastosai.config.PricingProperties;
import com.teng.app.gastosai.dto.CheckoutRequest;
import com.teng.app.gastosai.dto.CheckoutResponse;
import com.teng.app.gastosai.dto.PricingItem;
import com.teng.app.gastosai.dto.SubscriptionResponse;
import com.teng.app.gastosai.entity.BillingPeriod;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.List;

@RestController
@RequestMapping("/subscription")
@RequiredArgsConstructor
public class SubscriptionController {

    private final PaymentService paymentService;
    private final PricingProperties pricing;

    @PostMapping("/checkout")
    public CheckoutResponse startCheckout(
            @Valid @RequestBody CheckoutRequest request,
            @AuthenticationPrincipal User user) {
        String url = paymentService.startCheckout(user, request.period());
        return new CheckoutResponse(url);
    }

    @GetMapping
    public SubscriptionResponse current(@AuthenticationPrincipal User user) {
        var info = paymentService.currentSubscription(user);
        return new SubscriptionResponse(info.plan(), info.status(), info.currentPeriodEnd(), info.billingPeriod());
    }

    @GetMapping("/pricing")
    public List<PricingItem> pricing() {
        return List.of(
                new PricingItem(PlanKey.PREMIUM, BillingPeriod.MONTHLY, pricing.getPremiumMonthlyCentavos(), pricing.getCurrency()),
                new PricingItem(PlanKey.PREMIUM, BillingPeriod.ANNUAL, pricing.getPremiumAnnualCentavos(), pricing.getCurrency())
        );
    }
}
