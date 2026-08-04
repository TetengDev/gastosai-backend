# Go-Live & Pricing-Enablement Strategy

_Last updated: 2026-08-04 · Verified against backend `0.65.1`, contract `1.1.0` · Status:
advisory — no code changes in this doc_

This document answers one question: **what has to happen before GastosAI can charge money?**

An earlier revision (2026-07-13) described a pre-PayMongo world and is wrong in the expensive
direction: it listed as "missing" a payment stack that has since shipped. Every claim below was
re-checked against the code before this rewrite landed; each row cites the class, endpoint or
file that backs it.

---

## TL;DR

- The **entitlement, quota and tier system is built and tested**, sitting inert behind one flag
  (`MONETIZATION_ENFORCE`, default `false`).
- The **payment stack is also built**: `PayMongoProvider` (Checkout Sessions),
  `POST /subscription/checkout`, signature-verified `POST /webhooks/paymongo`,
  `GET /subscription`, `GET /subscription/pricing`, and `Pricing.tsx` + `CheckoutReturn.tsx` +
  `BillingSection.tsx` on web. All three payment endpoints are published in contract `1.1.0`.
- **What remains is not a build.** It is: live PayMongo credentials and a registered webhook
  (owner-side), an enforcement dry-run against a real database, auto-trial enrolment, the annual
  plan seed, and a **mobile upgrade path that does not exist at all** — mobile is the one hard
  code blocker on flipping enforcement.
- Sequence: wire the PayMongo account → prove a test-mode purchase end-to-end → close the
  trial/annual/mobile gaps → validate enforcement → flip `MONETIZATION_ENFORCE=true`.

---

## 1. Current state

### 1.1 Entitlements and tiers — built

| Area | Status | Evidence |
|---|---|---|
| Tiers | FREE / PREMIUM / TRIAL | `PlanKey`, `SubscriptionPlan`, `UserSubscription` |
| Feature gating | 11 `FeatureKey`s, central authority | `EntitlementService.canAccessFeature` / `requireFeatureAccess` |
| Master on/off | `gastos.monetization.enforce` (default `false`) | `MonetizationProperties`; short-circuits to full access when off |
| AI quotas | FREE 30 / PREMIUM 300 / TRIAL 50 (vision sub-caps 5/50/10); absolute ceiling 1000/mo | `AiManagedProperties`, `AiQuotaService.assertWithinQuota` |
| Category caps | per-plan (FREE limited) | `CategoryLimitProperties`, gated by enforce |
| Chat personas | professional/genz gated → graceful fallback to plain | `EntitlementService` chat-mode path |
| Admin bypass + tier testing | admin always entitled; view-as-plan toggle | `ViewAsInterceptor` / `ViewAsContext`, web `AdminViewAsToggle.tsx` |
| Per-tier test users | `free@` / `premium@` / `trial@gastosai.dev` seeded with subscriptions | `AppDataLoader` (behind the seed flag) |
| Subscription state machine | ACTIVE / TRIAL / INACTIVE / EXPIRED / CANCELLED; `grantsAccess()` | `SubscriptionStatus`, `SubscriptionService.activate/cancel` |
| Pricing decision | PREMIUM ₱149/mo, ₱1,290/yr served from config | `PricingProperties` (14900 / 129000 centavos), `docs/pricing/pricing-memo-2026-06-19.md` |

### 1.2 Payments — built (this is the part the old revision got wrong)

| Piece | Status | Evidence |
|---|---|---|
| `PaymentProvider` implementation | **shipped** — PayMongo Checkout Sessions, card + GCash + Maya, metadata carries user/plan/period | `payment/PayMongoProvider`, `PayMongoRestClientConfig`, `PayMongoProperties` |
| Checkout endpoint | **shipped** — `POST /subscription/checkout` (authenticated) returns the provider URL and records a `PENDING` row | `SubscriptionController.startCheckout`, `PaymentService.startCheckout`, `PaymentCheckout` / `CheckoutStatus`, `V19__payment_checkout.sql` |
| Price list | **shipped** — `GET /subscription/pricing`, public, monthly **and** annual | `SubscriptionController.pricing`, `PricingItem` |
| Current plan | **shipped** — `GET /subscription` returns plan, status, period end, billing period | `SubscriptionController.current`, `PaymentService.currentSubscription` |
| Webhook → activation | **shipped** — `POST /webhooks/paymongo`, HMAC-verified with a 5-minute replay window, `checkout_session.payment.paid` → `SubscriptionService.activate` | `PayMongoWebhookController`, `PayMongoWebhookVerifier`, `PaymentService.handleWebhook` |
| Webhook idempotency | **shipped** — replay hits the already-`PAID` short-circuit; a concurrent double-delivery is stopped by a unique `(user_id, provider_ref)` constraint that rolls back as 5xx so the provider retries into the idempotent path | `PaymentService.handleWebhook`, `V20__user_subscription_provider_ref_unique.sql` |
| Fail-closed secret handling | **shipped** — a blank `PAYMONGO_WEBHOOK_SECRET` rejects every webhook rather than accepting an empty-key HMAC | `PayMongoWebhookVerifier.verify` |
| Test coverage | **shipped** — nine verifier tests (tampered body, wrong secret, malformed/absent header, stale timestamp, blank secret) and eight API tests including replay-idempotency and the duplicate-`provider_ref` path | `PayMongoWebhookVerifierTest`, `PaymentApiIntegrationTest` |
| Pricing / upgrade UI (web) | **shipped** — `/pricing` plan cards with monthly/annual, `/billing/return` post-payment surface, Settings billing panel, `UpgradePrompt` and the navbar Upgrade link both route to `/pricing` | web `pages/Pricing.tsx`, `pages/CheckoutReturn.tsx`, `components/BillingSection.tsx`, `components/UpgradePrompt.tsx` |
| Web billing kill-switch | **shipped** — `VITE_BILLING_ENABLED=false` hides every payment surface (free-launch mode); any other value keeps them visible | web `config/billing.ts`, gated in `App.tsx` and `Navbar.tsx` |
| Cost instrumentation | **shipped** — per-call input/output/total tokens and an estimated USD cost, with an admin summary | `AiUsage`, `AiUsageService.record`, `LlmUsage`, `GET /admin/ai-usage/summary`, `GET /admin/observability/cost` |
| Contract publication | **shipped** — `/subscription`, `/subscription/checkout`, `/subscription/pricing`, `/webhooks/paymongo` all in `contract/openapi.json`, published as `1.1.0` | `contract/package.json`, `OpenApiContractTest` |

The **interim admin activation endpoint** the old revision proposed was never built and is no
longer needed: self-serve checkout exists, so hand-onboarding an early adopter is a database
operation, not a missing feature.

### 1.3 What is actually missing

| Gap | Impact | Blocker? |
|---|---|---|
| Live PayMongo credentials + registered webhook | `PAYMONGO_SECRET_KEY` / `PAYMONGO_WEBHOOK_SECRET` default to blank (`application.properties`), so checkout cannot reach PayMongo and every webhook is rejected 401 | **YES — owner-side, see §4** |
| Enforcement never validated against a real database | `MONETIZATION_ENFORCE=true` is exercised only by `EntitlementEnforcementIntegrationTest` / `EntitlementBetaIntegrationTest`; it has never been run against a seeded Postgres with all three tiers | **YES** |
| **No mobile upgrade path** | `gastosai-mobile` has no subscription, pricing or checkout surface — `more/index.tsx` tells the user "Pricing, billing and admin tools live on the web app". Enforce today and a mobile user hits 402 with nowhere to go | **YES** |
| Auto-trial enrolment on signup | `AuthService.register` seeds categories and returns a session; it creates no subscription. TRIAL exists only as a seeded test user | Medium — decide before launch, not a code blocker |
| Annual plan seed | `EntitlementSeeder` seeds one PREMIUM row at 14900 centavos with a `// TODO annual price ₱1290`. Annual is nonetheless purchasable end-to-end — `BillingPeriod.ANNUAL` prices from `PricingProperties` (129000) and extends the period a year — so the gap is the recorded plan price, not the flow | Low |
| No self-serve cancel / downgrade | `SubscriptionService.cancel` exists but no controller calls it; ending a plan is an admin action | Medium — required before charging real money |
| Checkout expiry / abandonment | `CheckoutStatus.EXPIRED` is declared and never written; an abandoned session stays `PENDING` forever | Medium |
| Reconciliation | Nothing detects a checkout that succeeded at PayMongo but never activated locally (e.g. every webhook retry exhausted) | Medium |

Deployment gaps are deliberately not in this table — see §5.

---

## 2. Provider decision (settled)

PayMongo, chosen and implemented. Recorded here so the reasoning is not re-litigated:

| Provider | PH support | Methods | Settlement | Verdict |
|---|---|---|---|---|
| **PayMongo** | Native (PH company) | Cards, **GCash**, **Maya**, GrabPay | PHP to PH bank | **Chosen and shipped** |
| Xendit | SE-Asia, PH-supported | Cards, GCash, bank | PHP | Fallback if PayMongo onboarding stalls |
| Stripe | Limited in PH | Cards | Often no local PHP payout to PH entities | Best DX, payout risk — not primary |

GCash is dominant for the target segments (young BPO/office pros, freelancers, OFW remittance
managers per the pricing memo), so first-class GCash support decided it. `PayMongoProvider`
requests `card`, `gcash` and `paymaya` on every checkout session.

---

## 3. Remaining sequence

### Step 1 — Wire the PayMongo account (owner-side)
Keys, a public URL, a registered webhook, a test-mode purchase. Full runbook in §4. Nothing
here is an engineering task; nothing after it can be proven until it is done.

### Step 2 — Validate enforcement against a real database
1. Seeded Postgres, `MONETIZATION_ENFORCE=true`.
2. Log in as each seeded tier and confirm the matrix:
   - FREE hits 402 on premium features; AI request #31 blocked; 6th category blocked.
   - PREMIUM/TRIAL unlocked; quotas 300 / 50.
   - professional/genz chat falls back to plain for FREE.
   - Admin bypass intact; view-as-plan still switches the observed tier.
3. The integration tests cover the same matrix — treat this as the manual confirmation that the
   tests were testing the real thing.

### Step 3 — Close the gaps around the flow
- **Mobile upgrade path** (blocks the flip): subscription state in Settings, a pricing screen
  reading `GET /subscription/pricing`, checkout hand-off to the provider URL with return
  handling, and graceful 402s everywhere a gated feature is reachable.
- **Auto-trial on signup** if trials are part of the launch offer — otherwise decide explicitly
  that they are not, and stop describing TRIAL as a customer-facing tier.
- **Annual plan seed** — record ₱1,290/yr in `EntitlementSeeder` so the plan row matches what
  `/subscription/pricing` already advertises.
- **Self-serve cancel** — an endpoint over the existing `SubscriptionService.cancel`.
- **Expiry and reconciliation** — write `CheckoutStatus.EXPIRED` on abandonment, and a way to
  find a paid-at-provider/inactive-locally checkout.

### Step 4 — Flip the switch
Set `MONETIZATION_ENFORCE=true` only once Steps 1–3 hold against the production config. One
line, fully reversible. Until then features stay open and paying simply records PREMIUM.

---

## 4. PayMongo setup runbook

The code side is done (§1.2). What remains is account wiring, and it is all owner-side.

### 4.1 Create API keys
1. Sign up / log in at <https://dashboard.paymongo.com>.
2. **Developers → API Keys** → copy the **secret key** (`sk_test_...` while in test mode).
3. Stay in test mode until 4.4 passes; swap to `sk_live_...` only at real launch (live keys
   require completed PayMongo business activation).

### 4.2 Reach the backend from the internet
PayMongo webhooks cannot reach `localhost`. For a one-off verification an ngrok tunnel
(`ngrok http 8080`) is enough and keeps the deferred-deployment posture intact. A permanent
public URL is a deployment decision — see §5.

### 4.3 Register the webhook
1. **Developers → Webhooks → Create** (or via API).
2. URL: `https://<backend-host>/webhooks/paymongo`
3. Event: `checkout_session.payment.paid`
4. Copy the webhook **signing secret** (`whsec_...`).

### 4.4 Environment variables (host env / `.env` — never committed)

| Variable | Value |
|---|---|
| `PAYMONGO_SECRET_KEY` | `sk_test_...` / `sk_live_...` from 4.1 |
| `PAYMONGO_WEBHOOK_SECRET` | `whsec_...` from 4.3 |
| `FRONTEND_BASE_URL` | Frontend origin, e.g. `http://localhost:5173` locally — checkout success/cancel redirect to `<origin>/billing/return` |

Optional: `PAYMONGO_API_BASE_URL` overrides the API host (defaults to
`https://api.paymongo.com`); `VITE_BILLING_ENABLED=false` on web hides the payment UI entirely.

For a real launch add the standard prod prerequisites, fail-fast enforced at startup:
`JWT_SECRET`, `AI_KEY_ENCRYPTION_SECRET` (both non-default), `DB_URL` / `DB_USERNAME` /
`DB_PASSWORD`, `CORS_ALLOWED_ORIGINS`, an AI key (or `AI_ALLOW_SHARED_KEY=false`),
`RESEND_API_KEY`. Full checklist: `.env.prod.example`.

The verifier fails closed: with `PAYMONGO_WEBHOOK_SECRET` unset every webhook is rejected 401,
so payments never activate silently on a misconfigured box.

### 4.5 Test-mode purchase (the end-to-end proof)
1. Log in as a FREE user → **Pricing** → Upgrade (monthly).
2. Pay with a test card: `4343 4343 4343 4345`, any future expiry, any CVC. Test GCash/Maya are
   simulated in test mode.
3. Expect: redirect to `/billing/return?status=success` → entitlements refresh → Settings →
   Billing shows **PREMIUM ACTIVE** with the correct period end. Cancel instead should land on
   `/billing/return?status=cancelled`.
4. Cross-check: PayMongo dashboard shows the payment; webhook deliveries show `200`. A `401`
   means the signing secret does not match; a `5xx` is retried automatically — check logs, and
   note that the retry is expected to land on the idempotent already-`PAID` path.
5. Repeat delivery of the same event from the dashboard: the subscription must not
   double-activate and the period end must not move.

---

## 5. Deployment posture

**Deployment is deferred** — milestone `M5` in `../gastosai-app/docs/ROADMAP.md`. The ordinary
verification loop is local: Docker Postgres on `:5433`, local API, web on the Vite dev server,
mobile on Expo against the LAN address. Nothing in §3 requires a deploy except the webhook's
public URL, and §4.2's tunnel covers that for verification.

What a *paid* launch would additionally need, recorded so the decision is not rediscovered:

| Layer | Today | For a paid launch |
|---|---|---|
| Backend | Render free tier (sleeps after 15 min) | Always-on host — `compose.prod.yml` + Caddy auto-HTTPS, per `docs/deploy-oracle.md` |
| Frontend | Vercel | Vercel (unchanged) |
| Database | Supabase free (pauses ~1wk idle) | Supabase paid tier, or Postgres alongside the backend |
| Email | Resend HTTP (Render blocks SMTP) | Resend (unchanged) |
| TLS/proxy | — | Caddy (auto Let's Encrypt) via `compose.prod.yml` |

A paid product cannot sit on a backend that cold-starts or a database that pauses. That makes
always-on infra a hard prerequisite for *charging* — but not for any of the work in §3, which is
why the deferral holds.

---

## 6. Risks & open questions

- **Price is unvalidated at ₱149** — the memo infers willingness-to-pay from Spotify/Netflix,
  not measured for expense apps. Token and cost instrumentation now exists (`AiUsage`,
  `GET /admin/observability/cost`), so the margin question is answerable from data rather than
  assumption; a ₱99-vs-₱149 test on new signups is the cheapest next step.
- **Vision cost multiplier** — receipt vision (~₱0.25/receipt) is ~33× a chat message. The
  sub-cap bounds it, but heavy vision users still erode margin faster than the memo's average.
- **Refunds / disputes / invoicing** — not designed. Needed before scale, and partly before the
  first sale: see the self-serve cancel gap in §1.3.
- **Tax / receipts (BIR)** — selling in PH eventually needs official receipts. Out of scope for
  MVP; flag for legal before meaningful revenue.
- **Trials are half a feature** — TRIAL has a plan, a status and quotas but no way in. Either
  finish it or drop it from the customer-facing story.

---

## References
- `docs/pricing/pricing-memo-2026-06-19.md` — tier prices, quotas, unit economics, WTP segments
- `docs/capabilities.md` — product scope, tiers, `MONETIZATION_ENFORCE` behaviour
- `docs/deploy-oracle.md` — production deployment walkthrough (deferred, `M5`)
- `.env.prod.example` — full production env-var checklist
- `../gastosai-app/docs/ROADMAP.md` — milestones `M3` (monetization go-live) and `M5` (deferred)
