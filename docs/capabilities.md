# GastosAI — Capabilities, Scope & Limitations

_Last updated: 2026-08-04 · App version 0.65.1 · API contract 1.1.0_

A consolidated, plain-language map of what GastosAI does today, how the subscription tiers
divide access, where the current limits are, and what is planned next. For the engineering
domain model see `ai/skills/project-context.md`; for pricing rationale see
`docs/pricing/pricing-memo-2026-06-19.md`.

---

## 1. What GastosAI is

A personal-finance tracker for the Philippines with an AI financial assistant. Users log
expenses (manually, by natural language, by CSV, or by snapping a receipt), set budgets and
savings goals, track recurring bills, and ask questions about their spending in plain
language. Built on Spring Boot 4.1 / Java 25 (backend), React + Vite + Tailwind (frontend),
PostgreSQL (Supabase), with OpenAI / Claude as the AI provider.

---

## 2. Capabilities by area

| Area | What works today |
|---|---|
| **Expenses** | Full CRUD, multi-currency (amount + exchange rate + base currency), CSV import, paginated list (`GET /expenses/page`, page size capped at 100), date-range filter, NL text parse → draft (`POST /expenses/parse`), PERSONAL/BUSINESS type + reimbursable flag. |
| **Categories** | Per-user (isolated per account), case-insensitive uniqueness, predefined seed set, delete reassigns expenses to *Uncategorized*, AI category suggestion. |
| **Budgets** | Per-category monthly limits, `GET /budgets/summary` → safe-to-spend, daily allowance, per-category status (ON_TRACK / WARNING ≥80% / OVER ≥100%). |
| **Rule-based budgeting** | 50-30-20 and 70-20-10 presets plus CUSTOM percentages (`GET`/`PUT /budget-rules`, `PUT /budget-rules/enabled`), categories mapped to NEEDS / WANTS / SAVINGS buckets (`PUT /budget-rules/buckets`), `GET /budget-rules/summary` → per-bucket target vs spent, remaining and unassigned spend for a month. |
| **Goals** | Savings goals with target amount/date, status derivation (ON_TRACK / BEHIND / COMPLETED / PAUSED), dashboard progress card. |
| **Recurring bills** | MONTHLY / WEEKLY / YEARLY recurrence, upcoming-bills computation, dashboard card. |
| **Reports & dashboard** | Monthly / category / daily / top-N / month-over-month endpoints; donut, bar, daily-trend charts, plain-English MoM sentence, top expenses. |
| **Receipt scanning** | `POST /ai/vision` → structured draft (amount, category, date, description, confidence) with a confirm-to-save flow. |
| **Alerts** | Budget-warning / budget-exceeded / spending-spike nudges, idempotent, mark-read / dismiss. |
| **AI assistant** | NL→SQL queries (SqlGuard-protected), three tone modes (plain / professional / GenZ), chatbot with 14+ CRUD tools (confirm-gated deletes), **conversation memory + history**, follow-up context resolution ("delete it"), cached AI insights (top category, month summary, recommendations). |
| **Export** | CSV export (formula-injection hardened). |
| **Auth** | Email + password (BCrypt), passwordless email magic-link, and Google sign-in (`POST /auth/google` verifies a Google Identity Services ID token and creates the account on first sign-in); JWT sessions (8h TTL). |
| **Billing** | PayMongo checkout (`POST /subscription/checkout`), signature-verified webhook (`POST /webhooks/paymongo`) that activates PREMIUM, current-plan lookup (`GET /subscription`) and price list (`GET /subscription/pricing`). |
| **Admin** | Contact/feedback submissions view, chat-audit log view, **view-as-tier** toggle for testing entitlements. |

---

## 3. Subscription tiers

> **Important:** monetization enforcement is **OFF by default** (`gastos.monetization.enforce=false`).
> During alpha/beta **every feature is unlocked for every user** — the tier system is wired and
> observable but inert. Enabling it later is a single config flip (`MONETIZATION_ENFORCE=true`),
> not a code change. **ADMIN accounts always bypass all limits.**

| | FREE (₱0) | PREMIUM (₱149/mo · ₱1,290/yr) | TRIAL (14 days) |
|---|---|---|---|
| Expense CRUD, budgets, goals, recurring | ✓ | ✓ | ✓ |
| AI insights (cached) | ✓ | ✓ | ✓ |
| AI chatbot — **plain** mode | ✓ | ✓ | ✓ |
| AI chatbot — **professional / GenZ** modes | ✗ (falls back to plain) | ✓ | ✓ |
| Receipt vision | ✓ (sub-capped) | ✓ | ✓ (sub-capped) |
| CSV export | ✓ | ✓ | ✓ |
| Standalone NL analytics query (`/ai/query`), PDF export†, forecasting†, anomaly†, advanced insights | ✗ | ✓ | ✓ |
| Custom categories | ✓ up to 5 | unlimited | unlimited |
| **AI requests / month (chat + vision pooled)** | **30** | **300** | **50 / window** |
| — of which receipt-vision sub-cap | **5** | **50** | **10** |

† `EXPORT_PDF`, `BUDGET_FORECASTING` and `ANOMALY_DETECTION` exist as `FeatureKey` values and are
granted per plan, but nothing is behind them yet — the entitlement is reserved, the feature is
unbuilt (§6).

"One AI request" = one chatbot turn **or** one receipt-vision upload. Cached insights do **not**
count. Absolute safety ceiling: 1,000 AI requests/month per account regardless of plan.

Prices come from the pricing memo and are served by `GET /subscription/pricing`; PayMongo checkout
is wired (§2, **Billing**) and takes effect once `PAYMONGO_SECRET_KEY` / `PAYMONGO_WEBHOOK_SECRET`
are set.

---

## 4. Easy on/off (for testers & ops)

| Control | Effect |
|---|---|
| `gastos.monetization.enforce` (`MONETIZATION_ENFORCE`) | `false` (default) = all features unlocked for all users. `true` = tiers enforced. |
| Admin **view-as-tier** toggle (UI) | Admin can preview the app as FREE / PREMIUM / TRIAL without changing their own plan. |
| Seeded test users (when sample seed is on) | `free@` / `premium@` / `trial@gastosai.dev` (passwords `free123` / `premium123` / `trial123`) + the admin account let you exercise each tier by logging in. |
| Category caps (`gastos.limits.categories.*`) | When enforced: FREE = 5, PREMIUM/TRIAL = unlimited. Only applied while `enforce=true`. |
| `gastos.ai.allowSharedKey` (`AI_ALLOW_SHARED_KEY`) | `true` = managed (shared-key) AI. `false` = bring-your-own-key. |
| `gastos.auth.google.client-id` (`GOOGLE_CLIENT_ID`) | Blank (default) = `POST /auth/google` answers `503` and the frontend hides the button. Set it — and the frontend's `VITE_GOOGLE_CLIENT_ID` — to the same OAuth client id to enable "Continue with Google". |
| `gastos.paymongo.*` (`PAYMONGO_SECRET_KEY`, `PAYMONGO_WEBHOOK_SECRET`) | Blank (default) = checkout cannot reach PayMongo. Set both to take real payments. |

---

## 5. Current limitations

- **Billing is wired but not switched on** — checkout, webhook, plan lookup and pricing exist
  (§2), yet the PayMongo credentials are blank by default and monetization enforcement is off, so
  nobody is charged today. There is also **no self-serve cancel or downgrade endpoint**:
  `SubscriptionService.cancel` exists but no controller calls it, so ending a plan is an admin
  action. (`docs/go-live-strategy.md` still describes the pre-PayMongo world and is being
  rewritten separately.)
- **Monetization not enforced** by default (intentional for alpha/beta).
- **Receipt vision cost** — gpt-4o-mini applies a ~33× image-token multiplier; vision has its
  own sub-cap but model choice is a known cost risk (see pricing memo §4).
- **Magic-link emails are logged locally** in dev — prod SMTP (`MAIL_*` + `FRONTEND_BASE_URL`) not
  yet configured, so real emails don't send.
- **Google sign-in ships disabled** — the endpoint and service are there, but `GOOGLE_CLIENT_ID`
  is blank by default so it answers `503` until configured. ID tokens are checked against Google's
  `tokeninfo` endpoint rather than verified offline; hardening that is a known follow-up.
- **No other social sign-in** (Apple, Facebook) — not started.
- **No receipt vault** — scanned receipts produce a draft expense but the image is not stored.
- **No source tracking** on expenses (manual vs text vs receipt vs import).
- **Dashboard** still loads the full expense list client-side for aggregation (paginated list
  exists for the Expenses table only).

---

## 6. Room for improvement (backlog)

Everything below was checked against the code on 2026-08-04 and is genuinely absent — no entity,
endpoint or service implements it. Entries that only exist as a reserved `FeatureKey` say so.

- Receipt vault (store images, statuses PENDING / CONFIRMED / REJECTED) — no image is persisted
  anywhere today.
- AI anomaly explanations — `ANOMALY_DETECTION` is a `FeatureKey` with no detector behind it.
- Category aliases & merchant→category rules — no merchant or alias concept exists in the schema.
- Project/client tagging for freelancers/business — no tag column or table.
- PDF export — `EXPORT_PDF` is a `FeatureKey`; only CSV export is implemented.
- Expense source tracking (manual vs text vs receipt vs import) — `Expense` has no source column.
- Parse-and-save one-shot endpoint — `POST /expenses/parse` returns a draft only; saving is a
  second call.
- Self-serve cancel / downgrade endpoint over `SubscriptionService.cancel` (§5).
- Cheaper vision path (downscale / alternate model) to protect margin — `VisionService` sends the
  image at full size to the configured provider model.

---

_For shipping status of each item see `../gastosai-app/docs/ROADMAP.md` and `CHANGELOG.md`._
