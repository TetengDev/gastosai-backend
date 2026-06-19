# GastosAI — Philippines Pricing Memo (managed-AI era)

_Date: 2026-06-19 · Author: pricing-agent · Status: decision-ready_

**Scope:** monetization model, PHP prices, and the per-tier monthly AI request quota that
engineering needs to cap the new shared-key cost. FX assumption throughout: **₱57 = US$1**
(assumed — verify against your card/GCash settlement rate).

---

## 1. Recommendation

Ship a **freemium subscription** with a single paid tier plus a time-boxed trial. Prices in
PHP, billed via GCash/Maya/card:

- **FREE — ₱0.** Full expense CRUD, budgets, goals, CSV export, AI **insights** (cached,
  near-zero marginal cost). AI **chatbot + receipt-vision capped at 30 AI requests/month.**
- **PREMIUM — ₱149/month or ₱1,290/year** (annual ≈ 7.2 months, ~28% off). Everything,
  **300 AI requests/month** (chat + vision pooled), PDF export, forecasting, anomaly
  detection, advanced insights.
- **TRIAL — 14 days, Premium-equivalent, but AI hard-capped at 50 requests** for the whole
  trial window — enough to feel the magic, not enough to be a churn-and-burn cost vector.

The repo stubs PREMIUM at ₱199 (`EntitlementSeeder` `priceCents=19900`). Recommend
**dropping to ₱149** (segment WTP + competitor data below). "AI request" = one chatbot turn
**or** one receipt-vision upload. Insights do **not** count (Caffeine-cached, marginal cost ≈ 0).

---

## 2. Why (each tied to a number)

1. **The chatbot is a 2–3x cost multiplier per message.** A single chat turn fires intent
   classification + (often) SQL fallback + always a summary → **2–3 gpt-4o-mini calls per
   user message**. Quota counts user *actions*, costed in *LLM calls*.
2. **Receipt vision is the cost bomb, not chat.** gpt-4o-mini applies a **~33x image-token
   multiplier** → ~25k input tokens per receipt vs ~1.5k for a chat call. One receipt ≈ 15
   chat messages in cost. Pooled quota + a vision sub-throttle protects margin.
3. **Insights are ~free to serve** (Caffeine cache, 15-min TTL per user+month) → give them
   away in FREE as the habit hook.
4. **PH WTP anchors to entertainment subs.** Spotify Individual ₱169/mo, Netflix Mobile ₱169.
   ₱149 sits just under the Spotify line — "less than my Spotify."
5. **Annual billing fixes heavy-user margin** — ₱1,290 prepay collects cash up front; average
   users under-consume the 300 quota → headroom becomes margin.

---

## 3. Segments (ranked by revenue potential × reachability)

| Rank | Segment | Job-to-be-done | Budget reality | Value metric | Est. WTP/mo |
|---|---|---|---|---|---|
| 1 | Young office / BPO pros (22–32, MM/Cebu) | "Where did my sweldo go?" | Pays ₱169 Spotify + ₱449 Netflix | AI chat + receipt scan | **₱120–180** |
| 2 | Freelancers / online sellers / gig | Separate business vs personal; quick logging | Lumpy income; pays to save time | Receipt vision + NL queries | **₱150–250** |
| 3 | Students / first-jobbers (18–24) | Budget allowance; build habit | Very price-sensitive, GCash-native | FREE insights + capped chat | **₱0–80** (mostly FREE) |
| 4 | OFW households / remittance managers | Track family remittance spend | Higher budget, lower app-savvy | Goals + monthly summary | **₱150–200** |

Segments 1+2 = paying base (highest WTP × FB/IG/TikTok reachable). Segment 3 = FREE funnel.

---

## 4. Unit economics (the math behind the caps)

**Token prices (researched, May 2026):** gpt-4o-mini = **$0.15 / 1M input**, **$0.60 / 1M output**.

**(a) One chatbot message** (~2.5 LLM calls avg): per call ~1,500 in + ~400 out tok (assumed
sizes — repo doesn't pin prompt length) → $0.000465/call → **≈ ₱0.066 per chat message**.
**(b) One receipt-vision upload:** ~25,000 in (33x mini multiplier) + ~1,024 out →
**≈ ₱0.25 per receipt**.
**(c) Insights:** cached → ~1 generation/user/month → **₱0.07/mo. Negligible.**

Cost-to-serve per active AI user/month:

| Profile | Chat | Receipts | Monthly AI cost |
|---|---|---|---|
| Typical paid | 120 | 20 | **₱13.0** |
| Heavy paid (maxes 300) | 250 | 50 | **₱29.0** |
| FREE (30 cap, vision-throttled) | 25 | 5 | **₱2.9** |

+ infra cushion ~₱5/paid user/month (Render/Vercel/Supabase free today).

**Gross margin:** PREMIUM @ ₱149 → typical 88% (worst-case max-out 77%). Annual ₱1,290
(₱107.50/mo-equiv) → 79% typical / 64% worst. FREE bounded at **~₱5/mo** by the 30 cap +
vision sub-cap 5 — acceptable CAC-equivalent.

**Margin-protection rules engineering must implement:**
1. Pooled quota counts user actions, but **vision gets its own sub-cap** (FREE 5, PREMIUM 50,
   TRIAL 10) — each receipt ≈ 15x a chat message.
2. FREE hard cap 30/month bounds worst-case FREE cost ≈ ₱5.
3. **mini is the wrong model for vision** (33x multiplier) — flag `detail:low` downscale or an
   alternate cheaper-per-image vision path as a follow-up optimization.

---

## 5. Tier table (the engineering numbers)

| | FREE | PREMIUM | TRIAL |
|---|---|---|---|
| Price / month | ₱0 | **₱149** | ₱0 (14 days) |
| Price / year | — | **₱1,290** (~₱107.50/mo, 28% off) | — |
| Expense CRUD, budgets, goals | ✓ | ✓ | ✓ |
| AI insights (cached) | ✓ | ✓ | ✓ |
| AI chatbot (NL CRUD + NL→SQL) | ✓ (cap) | ✓ | ✓ (cap) |
| Receipt vision | ✓ (sub-cap) | ✓ | ✓ (sub-cap) |
| PDF export, forecasting, anomaly, advanced insights | ✗ | ✓ | ✓ |
| **AI MONTHLY REQUEST QUOTA (pooled)** | **30** | **300** | **50 / 14-day window** |
| **— of which vision sub-cap** | **5** | **50** | **10** |
| Per-minute rate limit | existing | existing | existing |
| Gross margin @ price | cost ~₱5 (funnel) | ~88% / 77% worst | n/a (acquisition) |

**Engineering numbers:** FREE 30 · PREMIUM 300 · TRIAL 50; vision sub-caps 5 / 50 / 10. Wire
as plan-feature limits behind `MONETIZATION_ENFORCE`. ADMIN bypasses.

---

## 6. Competitor snapshot (PH-relevant)

| App | Paid price | Free/paid boundary |
|---|---|---|
| Wallet by BudgetBakers | ~$2.99/mo (~₱170) / ~₱1,310/yr | Free manual; paid sync/unlimited |
| Spendee | ~$2.99/mo (~₱170) | Free 1 wallet; paid multi-wallet/bank sync |
| YNAB | $15/mo (~₱855) | No free tier — above PH WTP |
| Money Manager (Realbyte) | one-time ~$5–6 | Generous free; the "free default" anchor |
| Google Sheets / pen & paper | ₱0 | The real competitor for Segment 3 |

**Where GastosAI sits:** ₱149/mo undercuts the ₱170 Wallet/Spendee band; AI chatbot +
receipt vision differentiates. Annual ₱1,290 matches the ~₱1,310 Wallet/Spendee annual.

---

## 7. Risks & cheapest next experiment

Top assumptions that break the rec: (1) per-call token sizes are estimates — **instrument
real token usage before enforce** (highest-priority unknown); (2) vision 33x makes mini wrong
for images; (3) WTP at ₱149 inferred from entertainment subs, not measured for expense apps
(could force ₱99); (4) annual conversion offsetting heavy users is unproven.

**Cheapest first experiment:** ₱99 vs ₱149 paywall A/B for new signups + **passive token
logging** (log the provider `usage` field — no model change, ~1 dev-day). Validates real
cost-to-serve AND price elasticity. Add an in-app WTP micro-survey at the quota-hit moment.

### Found vs assumed
- **Found (cited):** gpt-4o-mini $0.15/$0.60 per 1M; vision ~33x multiplier; Spotify PH ₱169 /
  Netflix ₱169–₱619; Wallet/Spendee ~$2.99/mo, YNAB $15/mo. Repo: multi-call chat, cached
  insights, FREE=CSV-only, PREMIUM stub ₱199.
- **Assumed (labeled):** ₱57/USD; per-call token sizes (1.5k/400); usage profiles; ₱5 infra
  cushion; PH WTP band. Validate via token-logging + price A/B.

Sources: pricepertoken.com (gpt-4o-mini), finout.io (OpenAI 2026 pricing),
community.openai.com (vision token cost), philstarlife.com (Spotify PH), unbox.ph (Netflix PH),
getfinny.app (budget app pricing).
