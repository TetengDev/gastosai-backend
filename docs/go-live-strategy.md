# Go-Live & Pricing-Enablement Strategy

_Last updated: 2026-07-06 · Status: advisory (no code changes in this doc)_

This document answers one question: **what has to happen before GastosAI can charge money?**
It maps the current state, the blockers, and a sequenced path from today's alpha/beta
(full access, `MONETIZATION_ENFORCE=false`) to a paid, production launch.

---

## TL;DR

- The **entitlement, quota, and tier system is fully built and tested** — it sits inert behind
  a single flag (`MONETIZATION_ENFORCE`, default `false`).
- The **one true blocker is payments**: `PaymentProvider` is an interface with **no
  implementation**, there is **no checkout endpoint, no webhook, and no pricing/upgrade UI**.
  Until that exists, flipping enforcement on would only lock features with no way for a user to pay.
- **Recommended provider: PayMongo** (Philippines-native — cards, GCash, Maya, GrabPay; PHP settlement).
- **Recommended sequence:** validate enforcement in staging → instrument token usage → build
  PayMongo checkout + webhook + pricing UI → interim manual admin activation → production deploy
  on Oracle Always Free → flip `MONETIZATION_ENFORCE=true`.

---

## 1. Current state

### 1.1 What's ready (no work needed)

| Area | Status | Evidence |
|---|---|---|
| Tiers | FREE / PREMIUM / TRIAL | `PlanKey`, `SubscriptionPlan`, `UserSubscription` |
| Feature gating | 11 `FeatureKey`s, central authority | `EntitlementService.canAccessFeature` / `requireFeatureAccess` |
| Master on/off | `gastos.monetization.enforce` (default false) | `MonetizationProperties`; short-circuits to full-access when off |
| AI quotas | FREE 30 / PREMIUM 300 / TRIAL 50 (vision sub-caps 5/50/10); absolute ceiling 1000/mo | `AiManagedProperties`, `AiQuotaService.assertWithinQuota` |
| Category caps | per-plan (FREE limited) | `CategoryLimitProperties`, gated by enforce |
| Chat personas | professional/genz gated → graceful fallback to plain | `EntitlementService` chat-mode path |
| Admin bypass + tier testing | admin always entitled; view-as-plan toggle | `ViewAsInterceptor`, `AdminViewAsToggle` |
| Per-tier test users | `free@` / `premium@` / `trial@gastosai.dev` seeded with subscriptions | `AppDataLoader` (behind seed flag) |
| Subscription state machine | ACTIVE / TRIAL / INACTIVE / EXPIRED / CANCELLED; `grantsAccess()` | `SubscriptionStatus`, `SubscriptionService.activate/cancel` |
| Pricing decision | PREMIUM ₱149/mo seeded; memo decision-ready | `docs/pricing/pricing-memo-2026-06-19.md`, `EntitlementSeeder` |

### 1.2 What's missing (blockers)

| Gap | Impact | Blocker? |
|---|---|---|
| `PaymentProvider` implementation | interface only — no way to take money | **YES** |
| Checkout endpoint (`POST /checkout` → provider session URL) | user can't start a purchase | **YES** |
| Webhook → `SubscriptionService.activate()` | payment success never becomes an active subscription | **YES** |
| Pricing / upgrade UI | no page for a user to choose + pay for a plan | **YES** |
| Auto-trial enrolment on signup | trials only exist via seeded test users | Medium (nice-to-have at launch) |
| Token-usage logging | quota counters exist, but real cost-to-serve is unmeasured | Medium (validates margin) |
| Annual plan seed (₱1,290/yr) | only monthly seeded; memo TODO | Low |

---

## 2. Payment provider recommendation

| Provider | PH support | Methods | Settlement | Verdict |
|---|---|---|---|---|
| **PayMongo** | Native (PH company) | Cards, **GCash**, **Maya**, GrabPay | PHP to PH bank | **Recommended** — best local coverage + PHP payout; REST API + webhooks fit the existing `PaymentProvider` seam |
| Xendit | SE-Asia, PH-supported | Cards, GCash, bank | PHP | Strong alternative if PayMongo onboarding stalls |
| Stripe | Limited in PH | Cards | Often no local PHP payout to PH entities | Best DX, but payout/availability risk — not recommended as primary |

GCash is the dominant e-wallet for the target segments (young BPO/office pros, freelancers,
OFW remittance managers per the pricing memo), so first-class GCash support is decisive → **PayMongo**.

---

## 3. Recommended sequence

### Phase 0 — Validate enforcement (no new code, staging only)
1. In a staging DB, set `MONETIZATION_ENFORCE=true`.
2. Log in as each seeded tier and confirm the access matrix:
   - FREE hits 402 on premium features; AI request #31 blocked; 6th category blocked.
   - PREMIUM/TRIAL unlocked; quotas 300 / 50.
   - professional/genz chat falls back to plain for FREE.
   - Admin bypass intact.
3. Existing tests already cover most of this (`EntitlementEnforcementIntegrationTest`,
   `EntitlementBetaIntegrationTest`). Treat this as a manual smoke confirmation.

### Phase 1 — Instrument cost (small feat slice)
- Log the provider's token-usage per AI call into `ai_usage` (add a `tokens` column).
- Purpose: validate the ₱149 margin assumption from the pricing memo against real cost-to-serve
  **before** committing to the price. Cheapest possible pricing experiment.

### Phase 2 — Payments (the real work; multi-slice feat)
1. **Backend:** implement `PayMongoPaymentProvider` (`key()`, `createCheckoutUrl(user, plan)`),
   a `POST /checkout` endpoint, and a **webhook** endpoint that verifies the PayMongo signature and
   calls `SubscriptionService.activate(user, plan, "paymongo", providerRef, periodEnd)`.
2. **Frontend:** a pricing/upgrade page (plan cards, "Upgrade" CTA → checkout URL redirect) and a
   post-payment return + "manage subscription / cancel" surface. Wire `UpgradePrompt` to it.
3. **Secrets:** `PAYMONGO_SECRET_KEY`, `PAYMONGO_WEBHOOK_SECRET` (never committed).
4. **Interim:** ship a manual **admin activation endpoint** first (admin sets a user's plan) so early
   adopters can be onboarded by hand while self-serve checkout is still being built/tested.

### Phase 3 — Production deploy
- Move to **Oracle Cloud Always Free** (always-on ARM VM, `backend/compose.prod.yml` + Caddy auto-HTTPS)
  — see `docs/deploy-oracle.md`. Current Render free tier sleeps after 15 min.
- Frontend on Vercel, DB on Supabase (or the Oracle VM's Postgres).
- Required prod secrets (fail-fast enforced): `JWT_SECRET`, `AI_KEY_ENCRYPTION_SECRET` (must differ
  from dev defaults), `DB_*`, an AI provider key (or `AI_ALLOW_SHARED_KEY=false` for BYOK),
  `RESEND_API_KEY` (email), `CORS_ALLOWED_ORIGINS`, `DOMAIN`, plus the PayMongo keys above.

### Phase 4 — Flip the switch
- Set `MONETIZATION_ENFORCE=true` in production only once Phase 2 is live and Phase 0 passed against
  the production config. This is a one-line change and is fully reversible.

---

## 4. Deployment posture (current vs recommended)

| Layer | Current (documented) | Recommended for paid launch |
|---|---|---|
| Backend | Render free (sleeps 15 min) | **Oracle Always Free** (always-on ARM, Singapore) |
| Frontend | Vercel | Vercel (unchanged) |
| Database | Supabase free (pauses ~1wk idle) | Supabase paid tier or Postgres on the Oracle VM |
| Email | Resend HTTP (Render blocks SMTP) | Resend (unchanged) |
| TLS/proxy | — | Caddy (auto Let's Encrypt) via `compose.prod.yml` |

A paid product cannot sit on a backend that cold-starts or a DB that pauses — Phase 3's move to
always-on infra is a hard prerequisite for charging.

---

## 5. Risks & open questions

- **Price is unvalidated at ₱149** — the memo infers WTP from Spotify/Netflix, not measured for
  expense apps. Phase 1 token logging + a ₱99-vs-₱149 A/B on new signups is the cheapest way to learn.
- **Vision cost multiplier** — receipt-vision (~₱0.25/receipt) is ~33× a chat message; heavy vision
  users erode margin faster than the memo's average assumes. Watch after Phase 1 instrumentation.
- **Refunds / disputes / invoicing** — not yet designed; needed before scale, not before first sale.
- **Tax/receipts (BIR)** — selling in PH eventually needs official receipts; out of scope for MVP but
  flag for legal before meaningful revenue.

---

## References
- `docs/pricing/pricing-memo-2026-06-19.md` — tier prices, quotas, unit economics, WTP segments
- `docs/deploy-oracle.md` — production deployment walkthrough
- `docs/capabilities.md` — product scope, tiers, `MONETIZATION_ENFORCE` behavior
- `backend/.env.prod.example` — full production env-var checklist
