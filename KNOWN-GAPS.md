# KNOWN-GAPS.md

Places where `CLAUDE.md` / `CONTRACT.md` describe a target the code has not reached yet.

These are recorded rather than fixed because each is a substantial change in its own right,
and bundling them into the monorepo→polyrepo split would have meant changing the data model
and the repository layout at the same time — with no clean rollback if either went wrong.
The split preserved behaviour exactly; these are the follow-ups.

Keep this file honest. When a gap closes, delete its entry.

---

## 1. Money is `BigDecimal`, not integer centavos

**Target:** money is an integer number of centavos (`₱150.75` = `15075`).
**Today:** `Expense.amount` and every monetary DTO field are `BigDecimal(19,4)`, serialized as
a JSON number at full precision. `OpenApiContractTest.specDeclaresNoFloatingPointMoney` asserts
no money-bearing field is ever `float`/`double`, so precision is not silently lost — but the
representation is not the one the contract describes.

**Why it is not done here:** this is simultaneously
- a **breaking contract change** — every amount field changes type, so it needs a major
  contract version plus `/api/v2` with `/api/v1` kept live;
- an **expand-contract DB migration** across the expense, budget, goal, and recurring tables;
- a **client migration** touching every display and input path.

Per `CONTRACT.md` that is an ordered sequence across repos, not a single change. Sequence it
after the split has settled.

**Scope:** 22 controllers, the reporting JPQL aggregates, and all money formatting in
`gastosai-web`.

---

## 2. Timestamps are serialized naive — no `+08:00` offset

**Target (`CONTRACT.md`, invariant 4):** timestamps are ISO 8601 with `+08:00`; a naive
timestamp is a bug.
**Today:** every `date-time` field serializes without an offset. Observed against a running
backend:

```
GET /expenses   date      = 2026-06-26T12:00:00
GET /goals      createdAt = 2026-07-27T07:06:10.599188
```

The contract declares these as `string / date-time`, and OpenAPI's `date-time` means RFC 3339
— which **requires** an offset. So the spec is currently more correct than the implementation:
a generated client is entitled to parse these as absolute instants, and instead gets a value
whose meaning depends on the reader's timezone.

**Why it matters here specifically:** the app's day/month rollups are `Asia/Manila`. A client
in any other zone that does `new Date("2026-06-26T12:00:00")` interprets it as *local* time,
so an expense can land in the wrong day — and therefore the wrong monthly total — with no
error anywhere.

18 fields across the schema set are affected (`ExpenseResponse.date`,
`GoalResponse.createdAt`, `AiUsageResponse.resetsAt`, the `ChatMessageDto`/`AlertResponse`
`createdAt`s, and so on).

**Fix:** serialize `OffsetDateTime`/`ZonedDateTime` at `+08:00` rather than `LocalDateTime`,
or configure Jackson with an explicit zone. This is **not** a breaking contract change — the
declared type stays `date-time`; the values simply start honouring it — so it can ship as a
minor contract version. Worth doing before mobile exists, since a phone genuinely is in an
arbitrary timezone.

---

## 3. Repository and migration tests run on H2, not Testcontainers

**Target:** repository and migration tests run against real PostgreSQL via Testcontainers.
**Today:** `src/test/resources/application.properties` points at H2 in PostgreSQL
compatibility mode with `ddl-auto=create-drop` and **Flyway disabled** — the test schema is
derived from the entities, not from the migrations.

**Why this matters:** the migrations are never exercised by the test suite. A migration that
fails on a real empty PostgreSQL database would pass CI. The `contract-spec` path proves the
app boots against real Postgres, but no test asserts the migration chain applies cleanly.

**Fix:** add a Testcontainers-backed profile for repository and migration tests, keeping H2
for the fast unit-ish slices if desired. Assert `V1..Vn` applies to an empty database and that
`ddl-auto=validate` then passes.

---

## 4. `docs/` was carried into the backend wholesale

`docs/` contains material that is not backend-specific (brand assets, pricing, go-live
strategy, some frontend-facing guides). The split gave the whole directory to the backend
because that is where the deployment and observability docs live.

**Fix:** move the frontend- and product-facing documents to `gastosai-web` or a separate docs
location, and leave `docs/` here as backend architecture + operations only.

---

## 5. Files intentionally dropped in the split

The following existed at the monorepo root and were **not** carried into either repo:
`CHANGELOG.md`, `LICENSE`, `AGENTS.md`, `.githooks/`, `ai/`, `brand/`, `qa-csv/`,
`render.yaml`, `scripts/*.ps1` (the tooling set), and the root `README.md`.

Consequences already handled: `auto-release.yml` no longer reads `CHANGELOG.md` (it uses
GitHub's generated notes), and `scripts/` was recreated with only what the deploy path needs.

Consequences **not** handled — decide deliberately:
- **`LICENSE`** is gone from both repos. Re-add if these are ever published.
- **`.githooks/`** carried the `commit-msg` format linter. Conventional-commit messages are
  now unenforced locally.
- **`scripts/bump-version.ps1`** was the version-bump helper. Version bumps are manual until
  it is reinstated per repo.
- **`CHANGELOG.md`** history now lives only in the archived monorepo.
