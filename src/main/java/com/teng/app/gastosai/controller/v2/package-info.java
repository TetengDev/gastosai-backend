/**
 * The {@code /api/v2} surface: the same endpoints as {@code /api/v1}, with money as integer
 * centavos.
 *
 * <p><strong>Every controller here is an adapter over its v1 counterpart, not a reimplementation of
 * it.</strong> Each handler delegates to the v1 controller bean and converts only the DTO, so month
 * validation, the {@code force} flags, result capping, circuit breakers and entitlement resolution
 * have exactly one implementation. That is what makes the issue's third acceptance criterion —
 * "both versions read the same rows; there is no second source of truth" — a property of the code
 * rather than a promise: there is no second call path to drift.
 *
 * <p>Consequences of that shape, each deliberate:
 *
 * <ul>
 *   <li>{@code @RequiresFeature} is re-declared on the v2 handler. {@code FeatureAccessInterceptor}
 *       reads the annotation off the handler method the mapping resolved to, which is the v2 one;
 *       an annotation only on the delegate would not be seen and the plan gate would silently open.
 *   <li>{@code @ResponseStatus} is likewise re-declared, for the same reason — it is read from the
 *       resolved handler, so a missing one would turn a 201 into a 200.
 *   <li>Path-pattern interceptors and the security matchers are extended to the {@code /api/v2}
 *       prefix in {@code WebConfig} and {@code SecurityConfig}; see
 *       {@code PublicEndpoints.VERSION_PREFIXES}.
 * </ul>
 *
 * <p>{@code PayMongoWebhookController} deliberately has no v2 twin. A webhook is called by PayMongo
 * against a URL configured in their dashboard, not by a client that repoints a base URL, and its
 * payload carries no gastosai money DTO. Mirroring it would add a second anonymous entry point for
 * no caller.
 */
package com.teng.app.gastosai.controller.v2;
