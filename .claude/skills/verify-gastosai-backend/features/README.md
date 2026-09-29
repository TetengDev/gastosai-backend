# gastosai-backend verification map

This directory is the maintained source for verifying the behavior of the gastosai API as a client
sees it. Read this index before driving the API, then use the matching feature file as the recipe.

A proof that drives one convenient endpoint is incomplete when the feature file lists others.

The API has no screen, so "user POV" here means **client POV**: the call the web app or the mobile
app actually makes, on the surface it pins. A proof that reaches past that — straight into Postgres,
into a service class, into a test-only route — proves something the user cannot reach.

## Baseline preconditions

- Bring the stack up from the workspace: `python3 ../scripts/verify_local.py --up`.
- Re-check it read-only before driving:
  `python3 .claude/skills/verify-gastosai-backend/api.py doctor` — Postgres, health, build version,
  `/v3/api-docs`, both login surfaces, the anonymous `401`, and a seeded row count must all pass.
- The API is `http://localhost:8080`. **v1 is unprefixed** (`/expenses`), **v2 is `/api/v2`**
  (`/api/v2/expenses`). `/api/v1/...` is not a route and answers `404`.
- The account is `demo@gastosai.dev` / `demo123`, fixed at backend startup from `.env`. It is
  seeded with close to two hundred expenses (191 on 2026-09-24, and the count drifts as runs add
  rows), so assert on a delta, never on a total.
- `MONETIZATION_ENFORCE=false` locally: every feature unlocked. A plan-gate `403` means it is `true`.
- Never drive an instance this run did not confirm with the doctor check.
- There is one database, one demo account, and per-JVM in-memory rate-limit counters. Do not start
  a second run against the same instance.

## Driving conventions

- Start every recipe from the baseline state unless its preconditions say otherwise.
- Drive through [`../api.py`](../api.py). Below, `S=.claude/skills/verify-gastosai-backend/api.py`,
  run from this repo's root.
- Treat every command as literal. Keep quoted bodies, paths and flags unchanged.
- Pass `--expect <status>` on every call you are asserting; without it the exit code only
  distinguishes `2xx` from the rest.
- Get a run id with `python3 $S runid` and stamp it into every `name` or `description` you create.
- Sweep with `python3 $S sweep --run-id <id> --month <YYYY-MM>`. Never
  `DELETE /api/v2/expenses` with no id — that is `deleteAll` for the account.
- Do not remove proof artifacts during cleanup.

## Proof and skip reporting

- Capture the request and the resulting state: the mutating call **and** a second `GET` reading it
  back, both saved with `--save`.
- Money proof states the surface and the stored integer: `15075` on `/api/v2/expenses`, `150.75` for
  the same row on `/expenses`. A number without its surface proves nothing.
- Scoping and role proof includes the refusal: the same path anonymously (`401`), and `/admin/**`
  with a non-admin token (`403`).
- Evidence lives in `~/.claude/gastosai-backend-verify/<date>/`, outside the repo — `target/` is
  wiped by `./mvnw clean` and `logs/` is gitignored. Attach what a reviewer must see with
  `python3 ../scripts/attach_evidence.py`.
- Record which feature file and endpoint produced each artifact, and the build version the doctor
  printed.
- Report an unreachable path with the attempted command and the unmet precondition. Do not report a
  skipped surface as verified through the other one — v1 and v2 are separate claims.

## Feature entry contract

Each feature file starts with an H1 title and one paragraph describing the client-visible behavior.
It then uses exactly four H2 sections in this order.

1. `Sub-features` lists short IDs with one line for each behavior.
2. `How to get to it (user POV)` lists every client entry point.
3. `Driving it with api.py` starts with `Preconditions:` and uses labeled bullets pairing each
   client action with an exact command and observable result.
4. `Gotchas` lists traps that can waste or invalidate a verification run.

## Features

- [Authentication](./auth.md) covers password sign-in on both surfaces, registration, magic link,
  and what a token does and does not carry.
- [Expenses](./expenses.md) covers create, read back, list, paging, update, delete, and the
  centavos-versus-decimal invariant across surfaces.
- [Budgets](./budgets.md) covers creating a budget against a category, the month regex, and the
  summary totals that the dashboard reads.
- [Goals](./goals.md) covers creating a goal, contributing to it, the progress percentage staying
  decimal, and deletion.
- [Authorization and scoping](./authorization.md) covers the public endpoint list, the anonymous
  `401`, per-user scoping, the `/admin/**` role gate, and the rate-limit buckets.

## Not yet mapped

Real client surfaces with no feature file yet. A proof that touches them is unguided; write the file
rather than improvising twice.

- **Recurring expenses** (`/api/v2/recurring`, `/upcoming`) and **alerts** (`/api/v2/alerts`).
- **Categories** as a feature of their own (`/api/v2/categories`) — used as a fixture by
  [budgets](./budgets.md), not verified for itself.
- **AI surfaces**: chat (`/api/v2/ai/**`, `/api/v2/chat/conversations`), insights (`/api/v2/ai/insights/*`), language, usage and
  quota, plus the bring-your-own-key path (`/api/v2/user/ai-settings`). These spend provider tokens
  and are quota-gated; map them with the mock boundary named.
- **Expense import/export** (`/api/v2/expenses/import`, `/export`, `/export/pdf`, `/import/template`)
  and **receipt parsing** (`/parse`, `/quick-add`).
- **Reports** (`/api/v2/expenses/report/monthly`, `/report/monthly-comparison`, `/report/category`).
- **Entitlements, features and subscription** (`/api/v2/user/entitlements`, `/api/v2/features`,
  `/api/v2/subscription/**`) and the **PayMongo webhook** (`POST /webhooks/paymongo`).
- **Submissions** (`POST /submissions` public, `GET` admin) and the three **admin** surfaces:
  AI usage, chat audit, observability.
- **User profile** (`/api/v2/user/**`) — note `/user` itself answers `500`, recorded in
  `../../../docs/security-pentest-2026-09-24.md`.
