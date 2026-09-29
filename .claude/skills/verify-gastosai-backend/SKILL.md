---
name: verify-gastosai-backend
description: Drive the real gastosai API (Spring Boot on :8080 against local Postgres on :5433) over HTTP and capture proof that a change works. Use when asked to verify, probe or prove backend behavior end to end, before reporting a backend change complete, when a PR needs runtime evidence rather than a green unit suite, and when a web or mobile failure has to be isolated to the API or cleared of it.
---

# Verify gastosai-backend

The API is the product's only source of truth: Spring Boot 3 / Java 25 on
`http://localhost:8080`, Postgres on `:5433`, two live surfaces (`v1` unprefixed and `v2` under
`/api/v2`). Web and mobile are clients of it. This skill is how an agent drives it the way a client
does — a real sign-in, a real bearer token, real rows — and comes back with evidence.

It does not replace [`verification-discipline`](../verification-discipline/SKILL.md) — that one
governs *how you report*; this one governs *how you drive*. Read both before claiming a backend
change works.

The feature recipes live in [`features/`](features/README.md). Read
[`features/README.md`](features/README.md) before driving anything.

## Launch

The stack is brought up by the workspace, not by hand. **From this repo the workspace is `..`**:

```bash
python3 ../scripts/verify_local.py --up     # Postgres :5433, API :8080, Vite :5173, demo login
```

That also starts the web dev server, which a pure API proof does not need but does not hurt; it is
one bring-up the whole workspace shares. The API alone is:

```bash
docker compose up -d      # only the `db` service; `backend`/`frontend` sit behind profiles: [app]
./mvnw spring-boot:run    # Flyway migrates on startup, then :8080 answers
```

Ready means `GET /actuator/health` returns `200` **and** a sign-in succeeds. Health alone is not
ready: the app answers health before the seed loader has written the demo account, so a login in
that window 401s against a perfectly live process.

Startup facts that change what "ready" means:

- **The demo and admin accounts are fixed at startup from `.env`.** The loader sets a password only
  when it *creates* the user, so editing `GASTOS_DEMO_PASSWORD` or `GASTOS_ADMIN_PASSWORD` against
  an existing database changes nothing and there is no change-password endpoint. A 401 here is
  configuration drift, not a credentials bug.
- **Seeding happens only when the `expenses` table is empty.** `GASTOS_SEED_SAMPLE_DATA=true`
  writes ~179 expenses per account spread over six months. A database carried over from a run with
  it off stays empty forever.
- **`MONETIZATION_ENFORCE=false` is the correct local posture** — every feature unlocked. A `403`
  with a plan-gate body while it is `true` is the gate working, not a defect.
- **The backend will not start without an AI key** matching `GASTOS_AI_PROVIDER` (`OPENAI_API_KEY`,
  or `CLAUDE_API_KEY` with `GASTOS_AI_PROVIDER=claude`).

Teardown:

```bash
python3 ../scripts/verify_local.py --down   # stops the API and the dev server; Postgres stays up
```

`--down` leaves Postgres running on purpose. Nothing here stops the database; do not add that.

## Doctor

One read-only check, before driving anything and again whenever a result looks wrong:

```bash
python3 .claude/skills/verify-gastosai-backend/api.py doctor
```

It starts nothing and writes nothing but the token cache. Require every row:

1. Postgres `:5433` running.
2. `GET /actuator/health` → `200`.
3. `GET /actuator/info` → `200` with a build version, so the evidence names the build it came from.
4. `GET /v3/api-docs` → `200` — the contract this instance actually publishes.
5. `POST /auth/login` → `200` (v1 surface live).
6. `POST /api/v2/auth/login` → `200` (v2 surface live).
7. `GET /api/v2/expenses` with no token → `401`. A `200` here means the security chain is not
   wired, and every later "it works" is worthless.
8. A seeded row count for the account, printed rather than asserted — it drifts as runs add rows.

**Never drive an instance you did not confirm with this check.** There is exactly one local
database and one demo account — see *Isolation* below.

## Drive

The harness is [`api.py`](api.py), in this directory and executable. Use it; do not hand-roll curl.
It caches the token `0600` and never prints it, so a transcript or an evidence file cannot leak a
live JWT.

```bash
S=.claude/skills/verify-gastosai-backend/api.py

python3 $S runid                                   # VERIFY-<epoch>; stamp every row you create
python3 $S login                                   # demo@gastosai.dev, cached for later calls
python3 $S req GET /api/v2/expenses --expect 200
python3 $S req POST /api/v2/expenses --expect 201 \
    --json '{"amount":15075,"description":"Coffee VERIFY-1","category":"Food"}'
python3 $S req GET /api/v2/expenses --anon --expect 401     # the negative half of a scoping proof
python3 $S sweep --run-id VERIFY-1 --month 2026-09
```

`--expect <status>` is what makes the call a test: the exit code is `0` only on that status.
Without it the exit code is `0` on any `2xx`.

Path rules that hold across this API:

- **v1 is unprefixed, v2 is `/api/v2`.** `POST /auth/login` and `POST /api/v2/auth/login` are both
  real routes. `/api/v1/...` is **not** a route and answers `404` — the docstring in
  `../scripts/http_check.py` still shows that spelling and is wrong.
- **Money differs by surface and nothing in the path says so.** v2 takes and returns integer
  centavos (`ExpenseRequestV2.amount` is a `Long`); v1 takes and returns a decimal. `15075` is
  ₱150.75 on `/api/v2/expenses` and ₱15,075.00 on `/expenses`, and both validate. Every money proof
  names the surface it drove.
- **Public paths are exactly `PublicEndpoints.RULES`** — `/auth/register`, `/auth/login`,
  `/auth/magic-link`, `/auth/magic-link/verify`, `/auth/google`, `/features`, `POST /submissions`,
  `GET /subscription/pricing`, each under both version prefixes, plus `/actuator/info`,
  `/actuator/health`, `/error`, `/swagger-ui/**`, `/v3/api-docs/**` and `POST /webhooks/paymongo`.
  Everything else is `authenticated()`, and `/admin/**` needs `ROLE_ADMIN`.
- **Swagger UI cannot drive an authenticated call.** The published spec declares no security
  scheme, so there is no Authorize control and every protected path answers `401` from the browser.
  That is TEN-202, a tooling gap, not a broken API. Do not report it as one, and do not use Swagger
  as the harness.

## Evidence

Two destinations, and they are not interchangeable:

- **A unit or integration suite:** `./mvnw test` output. It proves code, not a running instance, so
  it is never the whole proof for a behavior claim.
- **A runtime proof for an issue or PR:** stage it outside the repo, under
  `~/.claude/gastosai-backend-verify/<date>/`, and pass `--save` so the full body is on disk rather
  than truncated in a transcript:

  ```bash
  D=~/.claude/gastosai-backend-verify/$(date +%F); mkdir -p $D
  python3 $S doctor > $D/doctor.txt
  python3 $S req POST /api/v2/expenses --expect 201 --save $D/create.json \
      --json '{"amount":15075,"description":"Coffee VERIFY-1","category":"Food"}'
  ```

  Nothing inside this repo is a safe home for evidence: `target/` is gitignored *and* wiped by
  `./mvnw clean`, and `logs/` is gitignored too. Then attach what a reviewer must see:

  ```bash
  python3 ../scripts/attach_evidence.py TEN-129 ~/.claude/gastosai-backend-verify/<date>/create.json \
      --caption "what the reviewer should look at" --pr <N> --repo gastosai-backend
  ```

  The caption is the point of the tool: say what to look at, not what shipped.

Proof standards for this API:

- **Drive the real client path.** A row written straight into Postgres proves the schema, not the
  endpoint. If the claim is about an endpoint, go through it, with a token a real sign-in produced.
- **Capture the action and the resulting state.** A `201` alone is not a proof; read the row back
  with a second `GET` and save both.
- **Verify the side effect.** For money that means the stored integer, not a formatted string:
  assert `"amount": 15075` in the `GET`, and — when the claim spans surfaces — that `/expenses`
  reports the same row as `150.75`.
- **Assert the negative too.** Per-user scoping and role gates are proven by the refusal: the same
  path with no token (`401`), and with a non-admin token against `/admin/**` (`403`). A proof that
  only shows the happy call cannot tell a scoped endpoint from an open one.
- **Read text before pixels.** There is nothing to screenshot here; `--save` plus the printed
  status line is the cheapest complete record.
- **Mocks only at a boundary production already isolates** — the AI provider, the mail transport
  (blank `MAIL_HOST` falls back to logging), PayMongo. Never mock the database or the security
  filter chain to make a runtime proof pass.

## Cleanup

```bash
python3 $S sweep --run-id VERIFY-1 --month 2026-09   # data this run created
python3 ../scripts/verify_local.py --down            # processes this run started
```

- `sweep` deletes only rows whose name or description carries the run id, in dependency order
  (goals, expenses, budgets, categories). A sweep by bare name prefix would eat a concurrent run's
  data, and `DELETE /api/v2/expenses` with no id is `deleteAll` for the account — never use it as
  cleanup.
- Kill only what this run started. Never `pkill -f java` — that port and that JVM name are
  routinely the user's unrelated work, which is one of the traps `verify_local.py` exists to handle.
- The token cache in `/tmp/gastosai-verify/` holds a real JWT at `0600`. Leave it or delete the
  file; do not print it and do not copy it into evidence.
- **Cleanup never touches evidence.** `~/.claude/gastosai-backend-verify/<date>/` survives
  teardown. If a cleanup step would remove it, the step is wrong.

## Isolation — read before running two of anything

There is **one** local Postgres, **one** database and **one** demo account. Rate-limit counters are
per-JVM and in-memory by default (`gastos.ratelimit.redis.enabled=false`), so they are shared by
everything driving that instance.

So: **do not start a second verification run against an instance another run is driving.** Row
collisions are survivable — run-id stamping is real — but the limits are not: the public bucket is
10/min per IP, so two runs each signing in a few times make the *next* run fail for a reason that
has nothing to do with the code. Writes are 60/min, AI calls 20/min, magic links 200/day.

A second instance means a second database and a second port (`DB_URL`, `--server.port`, `VERIFY_API`
for this helper). That setup does not exist yet; say so rather than faking it.

## Gotchas

- **`429` reads like a broken endpoint and is not.** `POST /auth/login` sits in the public bucket
  at 10/min per IP. `api.py login` says so explicitly instead of reporting a credentials failure.
- **`/user` answers `500`, and it is not your change.** The request never reaches a controller; it
  falls through to the static-resource handler. Recorded during the 2026-09-24 pentest
  (`../docs/security-pentest-2026-09-24.md`).
- **The `X-View-As` header is admin-gated and does not affect `/features`.** Sending it as the demo
  account changes nothing; that is the gate holding.
- **`source` on an expense accepts only `MANUAL` and `RECEIPT_SCAN` from a client.** The other enum
  values bind but `ExpenseService` refuses them, and an unknown name is a `400` naming the field.
- **Amount bounds are surface-specific.** v2 is `@Min(1) @Max(Money.MAX_CENTAVOS)` on a `Long`, so
  `0` and negatives are `400`; v1 is `@DecimalMin(0.0, exclusive)` with 4 decimal places.
- **`month` parameters are `YYYY-MM` and validated by regex.** `2026-9` is a `400`, not an empty
  result.
- **Dates come back with `+08:00`.** An assertion built from the runner's local clock is a false
  failure waiting for a trip abroad.
- **A long-running JVM can hold an admin account that no longer matches `.env`.** Restarting the
  API re-applies the file; editing the file alone does not.
