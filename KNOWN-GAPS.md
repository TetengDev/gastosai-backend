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

## 2. Repository and migration tests run on H2, not Testcontainers

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

## 3. Timestamps are naive, not `+08:00`

**Target (Invariant 4 / `CONTRACT.md`):** timestamps are stored UTC and served as ISO 8601 with
an explicit `+08:00` offset; day/month logic runs in `Asia/Manila`.

**Today:** every response DTO in the repo serves `LocalDateTime`, which Jackson serializes with
no offset at all — not `+08:00`, not `Z`, nothing. There is a second half to this: no JVM
timezone is configured anywhere (no `-Duser.timezone`, no `spring.jackson.time-zone`), so the
naive value that goes out is whatever the *host's* default zone happens to produce, not reliably
`Asia/Manila`. Two hosts with different default zones would serve different wall-clock values
for the same instant.

**Why it is not done here:** fixing it means changing the type of every timestamp field on every
response DTO across the surface — `OffsetDateTime`/`ZonedDateTime` in place of `LocalDateTime` —
which is a breaking contract change on most of the API, not a local fix. Per `CONTRACT.md` that
needs a major contract version plus `/api/v2` kept live alongside `/api/v1`, the same shape as
gap #1's money migration.

**First raised:** PR #45, by `pr-reviewer` and upheld by `pr-review-auditor` as a deferred issue;
this entry is the durable record so it survives the PR scrolling out of view.

**Scope:** every response DTO carrying a timestamp, plus pinning the JVM/Jackson timezone to
`Asia/Manila` (or UTC with explicit conversion at the edge) so the served value stops depending
on the host.

---

## 4. Files intentionally dropped in the split

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

---

## 5. Branch protection is not enabled (blocked by plan/visibility)

The monorepo had two active rulesets — `Protect master` (block deletion and force-push,
require a PR, and require `Backend tests` / `Frontend audit & lint` / `Validate release branch`
as **strict** status checks) and `Protect release branches`. Neither transferred: rulesets are
repository settings, not files.

Recreating them via the API fails:

```
POST /repos/TetengDev/<repo>/rulesets
-> 403 Upgrade to GitHub Pro or make this repository public to enable this feature.
```

The monorepo is **public**, where rulesets are free. These repos are **private** under an org
on the **free** plan, where they are not.

Until this is resolved, nothing prevents a direct push to `main` or a force-push, and CI
passing is a convention rather than an enforced gate. Three ways out:

1. **Make the repos public** — matches the monorepo, costs nothing, and the source is already
   public there, so it exposes nothing new. Would also allow making the contract package
   public, removing the install token entirely.
2. **Upgrade the org to GitHub Team** — keeps them private and enables rulesets.
3. **Accept it** — rely on discipline. Weakest option; the release-branch guard in CI still
   runs on PRs, it just cannot be *required*.

The exact ruleset definitions are ready to apply the moment one of the above lands.

---

## 6. No concurrent-modification detection on expense/budget/goal/recurring updates

**Target:** an update path detects when two edits to the same row race, rather than silently
letting the later write win.

**Today:** `Expense`, `Budget`, `SavingsGoal` and `RecurringExpense` carry no `@Version` field, so
`READ COMMITTED` lets two genuinely concurrent edits resolve last-writer-wins with no conflict
signal on either the REST path (`PUT /expenses/{id}` and its siblings) or the chat edit path.

**What TEN-323 (PR #79) fixed, and what it did not:** the chat edit used to read and write in
separate transactions, so a concurrent edit landing between the two produced a **stale
carry-forward** — the chat write could silently overwrite fields it never re-read. TEN-323
collapsed the read and the write into one transaction (`inOneTransaction`), which closes that
defect. It does **not** add conflict detection: within that single transaction, `READ COMMITTED`
still resolves two truly concurrent edits last-writer-wins, same as the REST path always has. The
chat path is now no weaker than `PUT /expenses/{id}` — not stronger. This entry is that residual,
disclosed in `inOneTransaction`'s javadoc and in PR #79's body but not recorded here until now.

**Fix:** add `@Version` to `Expense`, `Budget`, `SavingsGoal` and `RecurringExpense` (an expand
migration — nullable/defaulted column, backfilled) and let JPA's optimistic-locking exception
surface as a 409 on both paths.

**First raised:** TEN-323 / PR #79 — https://github.com/TetengDev/gastosai-backend/pull/79 —
found by `pr-review-auditor`.

---
