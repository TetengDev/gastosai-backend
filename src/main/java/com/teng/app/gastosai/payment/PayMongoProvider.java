package com.teng.app.gastosai.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.teng.app.gastosai.config.PricingProperties;
import com.teng.app.gastosai.entity.BillingPeriod;
import com.teng.app.gastosai.entity.PlanKey;
import com.teng.app.gastosai.entity.User;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PayMongoProvider implements PaymentProvider {

    private final RestClient restClient;
    private final PricingProperties pricing;
    private final ObjectMapper objectMapper;
    private final String frontendBaseUrl;

    public PayMongoProvider(
            @Qualifier("payMongoRestClient") RestClient restClient,
            PricingProperties pricing,
            ObjectMapper objectMapper,
            @Value("${gastos.app.frontend-base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.restClient = restClient;
        this.pricing = pricing;
        this.objectMapper = objectMapper;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Override
    public String key() {
        return "paymongo";
    }

    @Override
    public PaymentCheckoutSession createCheckout(User user, PlanKey plan, BillingPeriod period) {
        int amount = period.centavos(pricing);
        String periodLabel = period == BillingPeriod.MONTHLY ? "Monthly" : "Annual";
        String description = "GastosAI Premium - " + periodLabel;

        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode data = root.putObject("data");
        ObjectNode attrs = data.putObject("attributes");

        attrs.put("amount", amount);
        attrs.put("currency", pricing.getCurrency());
        attrs.put("description", description);

        ArrayNode paymentMethods = attrs.putArray("payment_method_types");
        paymentMethods.add("card");
        paymentMethods.add("gcash");
        paymentMethods.add("paymaya");

        ArrayNode lineItems = attrs.putArray("line_items");
        ObjectNode item = lineItems.addObject();
        item.put("currency", pricing.getCurrency());
        item.put("amount", amount);
        item.put("name", description);
        item.put("quantity", 1);

        attrs.put("success_url", frontendBaseUrl + "/billing/return?status=success");
        attrs.put("cancel_url", frontendBaseUrl + "/billing/return?status=cancelled");

        ObjectNode metadata = attrs.putObject("metadata");
        metadata.put("userId", user.getId());
        metadata.put("planKey", plan.name());
        metadata.put("period", period.name());

        String responseBody = restClient.post()
                .uri("/v1/checkout_sessions")
                .body(root)
                .retrieve()
                .body(String.class);

        try {
            JsonNode response = objectMapper.readTree(responseBody);
            String sessionId = response.path("data").path("id").asText();
            String checkoutUrl = response.path("data").path("attributes").path("checkout_url").asText();
            return new PaymentCheckoutSession(sessionId, checkoutUrl);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse PayMongo checkout response", e);
        }
    }
}
