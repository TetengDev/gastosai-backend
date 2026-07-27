# Pre-PR quality checklist — gastosai-backend

Run this **before** pushing a branch or opening a pull request. Every item marked **Blocker**
must pass first.

Ported from the monorepo's `ai/skills/shared/pre-pr-checklist.md`. Adapted for the polyrepo:
this repo is a single stack, so the frontend half is gone; `CHANGELOG.md` was dropped in the
split; commands are POSIX rather than PowerShell. **Section 6 (execution testing) is unchanged
— it is the part that matters most and the part most often skipped.**

---

## 1. Static analysis

```bash
./mvnw compile
```

**Blocker:** any compilation error or unused import.

---

## 2. Tests

```bash
./mvnw test            # or ./mvnw verify for the JaCoCo report
```

**Blocker:** any failing test.

**Warning (not a blocker):** line coverage below 70% — note it in the PR body and add a
follow-up. New features need a unit test for service logic plus an integration test for the HTTP
happy path; bug fixes need a regression test that fails before the fix.

---

## 3. The published contract

This repo **owns** `@tetengdev/gastosai-api-contract`. `OpenApiContractTest` regenerates
`contract/openapi.json` during the test run.

```bash
git diff --exit-code contract/openapi.json
```

**Blocker:** a dirty spec that has not been committed. A controller or DTO change without a
regenerated spec ships a contract describing an API the backend no longer serves.

If the surface changed, decide the contract version now, not later — non-breaking → minor;
breaking → major **and** a new `/api/v2` path with `/api/v1` kept live (`CONTRACT.md`).

---

## 4. No secrets

```bash
git status --porcelain
git diff --staged
```

**Blocker:** any `.env`, API key, token, password or private key staged.

---

## 5. Version bump

**Blocker if application code changed.** Bump once per PR, not per commit, based on the
highest-impact change since the last release tag:

| Commit type | Bump |
|---|---|
| `fix:`, `perf:` | PATCH |
| `feat:` | MINOR |
| `!` or `BREAKING CHANGE:` | MAJOR |
| `docs:`, `chore:`, `ci:`, `refactor:`, `test:` | none |

The version lives in `pom.xml` `<project><version>` — read it with
`scripts/project-version.sh`, which parses the XML rather than grepping (a grep matches the
Spring Boot parent version and every pinned dependency).

---

## 6. Mandatory execution testing — no exceptions

**Every change must actually be run before the PR opens.** A green test suite, a clean compile
and a passing type-check are not sufficient on their own — the code must execute in its real
context.

Minimum: **≥ 90% of touched paths exercised at runtime.**

| Change type | Minimum execution required |
|---|---|
| API change | Start the backend, call the affected endpoint (curl or Swagger), confirm status **and** response shape |
| Migration | Apply against a real database; confirm `flyway_schema_history` and that `ddl-auto=validate` still starts |
| Script change | **Run the script.** Trigger every new branch, including the failure path. Observe output, not just exit code. |
| Docker / compose | `docker compose up`, confirm containers reach healthy |
| Config / env var | Restart with the new config, confirm the value is picked up |
| Seed/bootstrap | Start against a clean DB, confirm seeded data |

**Blocker:** application code changed with no runtime evidence. State in the PR body what was
run and what was observed — "tests pass" is not an answer to this item.

---

## 7. Schema, rollback and production readiness

- Migrations are **expand-contract** and append-only. Never rename, drop or retype a column that
  deployed code reads, in one step.
- Every migration PR states its step (expand | backfill | contract) and confirms
  `scripts/backup-before-migrate.sh` runs. It fails closed — no restorable dump, no migration.
- Prefer additive API changes. A breaking one needs a major contract version and `/api/v2`.
- **Rollback answer required:** "if this breaks production, how do I revert in under 5 minutes?"
  Acceptable: `git revert` is clean and redeployable, or the previous `IMAGE_TAG` is deployable.
  An image rollback does **not** undo a schema change — that is what expand-contract is for.

---

## 8. Branch and scope

```bash
git branch --show-current      # must not be main
git diff main...HEAD --stat    # scope matches the task
```

Branch lanes are enforced by CI (`Validate release branch`):

- `release/*` — application changes and version bumps
- `meta/*` — docs, CI, tooling. **Must not touch `src/` or change the version.**
- `dependabot/*` — dependency updates

---

## Summary

```
[ ] ./mvnw compile           — clean
[ ] ./mvnw test              — green
[ ] contract/openapi.json    — committed and current
[ ] No secrets staged
[ ] Version bumped (if app code changed)
[ ] EXECUTED at runtime      — ≥90% of touched paths, evidence in the PR body
[ ] Migration is expand-contract with a backup path (if schema changed)
[ ] Rollback answer identified
[ ] On a correct branch lane
```
