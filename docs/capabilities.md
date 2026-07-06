# GastosAI — Capabilities, Scope & Limitations

_Last updated: 2026-06-22 · App version 0.52.0_

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
| **Goals** | Savings goals with target amount/date, status derivation (ON_TRACK / BEHIND / COMPLETED / PAUSED), dashboard progress card. |
| **Recurring bills** | MONTHLY / WEEKLY / YEARLY recurrence, upcoming-bills computation, dashboard card. |
| **Reports & dashboard** | Monthly / category / daily / top-N / month-over-month endpoints; donut, bar, daily-trend charts, plain-English MoM sentence, top expenses. |
| **Receipt scanning** | `POST /ai/vision` → structured draft (amount, category, date, description, confidence) with a confirm-to-save flow. |
| **Alerts** | Budget-warning / budget-exceeded / spending-spike nudges, idempotent, mark-read / dismiss. |
| **AI assistant** | NL→SQL queries (SqlGuard-protected), three tone modes (plain / professional / GenZ), chatbot with 14+ CRUD tools (confirm-gated deletes), **conversation memory + history**, follow-up context resolution ("delete it"), cached AI insights (top category, month summary, recommendations). |
| **Export** | CSV export (formula-injection hardened). |
| **Auth** | Email + password (BCrypt) and passwordless email magic-link; JWT sessions (8h TTL). |
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
| Standalone NL analytics query (`/ai/query`), PDF export, forecasting, anomaly, advanced insights | ✗ | ✓ | ✓ |
| Custom categories | ✓ up to 5 | unlimited | unlimited |
| **AI requests / month (chat + vision pooled)** | **30** | **300** | **50 / window** |
| — of which receipt-vision sub-cap | **5** | **50** | **10** |

"One AI request" = one chatbot turn **or** one receipt-vision upload. Cached insights do **not**
count. Absolute safety ceiling: 1,000 AI requests/month per account regardless of plan.

Prices are decision-ready from the pricing memo; billing/payment integration is not yet built.

---

## 4. Easy on/off (for testers & ops)

| Control | Effect |
|---|---|
| `gastos.monetization.enforce` (`MONETIZATION_ENFORCE`) | `false` (default) = all features unlocked for all users. `true` = tiers enforced. |
| Admin **view-as-tier** toggle (UI) | Admin can preview the app as FREE / PREMIUM / TRIAL without changing their own plan. |
| Seeded test users (when sample seed is on) | `free@` / `premium@` / `trial@gastosai.dev` (passwords `free123` / `premium123` / `trial123`) + the admin account let you exercise each tier by logging in. |
| Category caps (`gastos.limits.categories.*`) | When enforced: FREE = 5, PREMIUM/TRIAL = unlimited. Only applied while `enforce=true`. |
| `gastos.ai.allowSharedKey` (`AI_ALLOW_SHARED_KEY`) | `true` = managed (shared-key) AI. `false` = bring-your-own-key. |

---

## 5. Current limitations

- **No payment/billing flow** — tiers are defined and enforceable but there is no checkout,
  subscription management, or pricing page; upgrades are manual/admin today. The sequenced path
  to charging money (payment provider, checkout, webhook, pricing UI) is in
  [docs/go-live-strategy.md](go-live-strategy.md).
- **Monetization not enforced** by default (intentional for alpha/beta).
- **Receipt vision cost** — gpt-4o-mini applies a ~33× image-token multiplier; vision has its
  own sub-cap but model choice is a known cost risk (see pricing memo §4).
- **Magic-link emails are logged locally** in dev — prod SMTP (`MAIL_*` + `FRONTEND_BASE_URL`) not
  yet configured, so real emails don't send.
- **No Google / social sign-in** yet (deferred).
- **No receipt vault** — scanned receipts produce a draft expense but the image is not stored.
- **No source tracking** on expenses (manual vs text vs receipt vs import).
- **Dashboard** still loads the full expense list client-side for aggregation (paginated list
  exists for the Expenses table only).

---

## 6. Room for improvement (backlog)

- Google sign-in (Google Identity Services → backend ID-token verify → app JWT).
- Rule-based budgeting (50-30-20 and other presets) — **in progress**.
- Monetization separation finalization (chat-mode gating, category cap, full FeatureKey
  enforcement) + payment provider (GCash / Maya / card).
- Receipt vault (store images, statuses PENDING / CONFIRMED / REJECTED).
- AI anomaly explanations; category aliases & merchant→category rules.
- Project/client tagging + PDF export for freelancers/business.
- Expense source tracking; parse-and-save one-shot endpoint.
- Cheaper vision path (downscale / alternate model) to protect margin.

---

_For shipping status of each item see the internal roadmap tracker and `CHANGELOG.md`._
