# Authorization and scoping

Every path except an explicit public list requires a bearer token, every read is scoped to the
signed-in user, and `/admin/**` additionally requires `ROLE_ADMIN`. On top of that sit four
rate-limit buckets. This is the feature most worth verifying by its refusals: a scoped endpoint and
an open one look identical from a happy-path call.

## Sub-features

- `public-list` — the public paths are exactly `PublicEndpoints.RULES` and answer without a token.
- `anon-401` — any other path without a token is `401`.
- `bad-token-401` — a malformed or foreign-signed token is `401`, not `500`.
- `cross-account` — another account's row id is `404`, not `403` and not the row.
- `admin-403` — `/api/v2/admin/**` with a non-admin token is `403`.
- `admin-200` — the same path with the seeded admin account works, proving the gate is a role check
  and not a broken route.
- `view-as-gated` — the `X-View-As` header is admin-only and changes nothing for a normal user.
- `rate-limits` — public 10/min, writes 60/min, AI 20/min, webhooks 600/min, magic links 200/day, all
  per-IP and per-JVM in memory by default.

## How to get to it (user POV)

- Every client call: the web and mobile API layers attach `Authorization: Bearer <token>` to all but
  the sign-in and pricing calls.
- The web app's 401 handling signs the user out and returns to `/login`; mobile's client
  differentiates `401` from a plan-gate `403`.
- Public, unauthenticated clients: the pricing page (`GET /api/v2/subscription/pricing`), the feature
  flags (`GET /api/v2/features`), the contact form (`POST /api/v2/submissions`) and the PayMongo
  webhook (`POST /webhooks/paymongo`).
- Admin surfaces: the web admin pages for submissions, chat audit and observability.

## Driving it with api.py

Preconditions: doctor passes (its anonymous `401` row is the first half of this feature);
`S=.claude/skills/verify-gastosai-backend/api.py`; `D=~/.claude/gastosai-backend-verify/$(date +%F)`.

- **Confirm a public path is public (`public-list`)**:

  ```bash
  python3 $S req GET /features --anon --expect 200
  python3 $S req GET /api/v2/features --anon --expect 200
  python3 $S req GET /actuator/health --anon --expect 200
  ```

  Observable: `200` for each; `/features` returned `{"csvImport": true, "chatAttachments": true}` on
  2026-09-24. The full list, both version prefixes included, is `PublicEndpoints.VERSIONED_RULES`
  plus `UNVERSIONED_RULES` — read it there rather than guessing, and treat anything not on it as
  closed.

- **Confirm everything else is closed (`anon-401`)**:

  ```bash
  python3 $S req GET /api/v2/expenses --anon --expect 401 --save $D/anon-401.txt
  python3 $S req GET /api/v2/user/entitlements --anon --expect 401
  python3 $S req GET /api/v2/goals --anon --expect 401
  ```

  Observable: `401` with an empty body. A `200` on any of these means the chain is not wired and
  every other proof in this skill is void.

- **Reject a forged token (`bad-token-401`)** — `api.py` has no flag for a fake token, so this one
  call goes through the workspace helper:

  ```bash
  python3 ../scripts/http_check.py http://localhost:8080/api/v2/expenses --bearer "not.a.jwt"
  ```

  Observable: `401`. A `500` here is a finding: an unparseable token must not reach an exception
  handler.

- **Prove per-user scoping (`cross-account`)** — two seeded accounts, one row id:

  ```bash
  python3 $S req GET /api/v2/expenses --expect 200 --max-body 300          # demo: note an id
  python3 $S --email free@gastosai.dev --password free123 login
  python3 $S --email free@gastosai.dev req GET /api/v2/expenses/<demo-id> --expect 404
  ```

  Observable: `404` with `"detail": "Expense not found: <id>"` — verified on 2026-09-24 against build
  `0.94.3`. The `404` rather than `403` is deliberate: a `403` would confirm the row exists. A proof
  of scoping needs this call; the happy `200` alone cannot distinguish scoped from open.

- **Prove the admin gate (`admin-403`)** — as the demo account:

  ```bash
  python3 $S req GET /api/v2/admin/ai-usage/cost-report --expect 403 --save $D/admin-403.json
  ```

  Observable: `403` with `"error": "Forbidden"` and the path echoed. Verified on 2026-09-24.

- **Prove the gate is a role check, not a dead route (`admin-200`)** — needs the seeded admin from
  `GASTOS_ADMIN_EMAIL` / `GASTOS_ADMIN_PASSWORD` in the backend's `.env`:

  ```bash
  python3 $S --email "$GASTOS_ADMIN_EMAIL" --password "$GASTOS_ADMIN_PASSWORD" login
  python3 $S --email "$GASTOS_ADMIN_EMAIL" req GET /api/v2/admin/ai-usage/cost-report --expect 200
  ```

  Observable: `200`. Read the credentials from the environment; never inline them, never save a
  response that echoes them. If `.env` leaves them blank there is no admin account — report that as
  unreachable rather than as a broken gate.

- **Check the view-as header is gated (`view-as-gated`)**:

  ```bash
  python3 ../scripts/http_check.py http://localhost:8080/api/v2/features --bearer "$(cat /tmp/gastosai-verify/token-demo_at_gastosai.dev)"
  ```

  …then repeat with `X-View-As` set, which `http_check.py` cannot add — so this sub-feature is
  currently **unreachable with the shipped helpers**. Say that rather than reporting it verified;
  the header's admin gating was checked by hand on 2026-09-24 and recorded in
  `../../../docs/security-pentest-2026-09-24.md`.

- **Observe a rate limit deliberately (`rate-limits`)** — only when the claim is about limiting:

  ```bash
  for i in $(seq 1 12); do
    python3 $S req POST /api/v2/auth/login --anon \
        --json '{"email":"demo@gastosai.dev","password":"wrong"}' --max-body 80
  done
  ```

  Observable: `401` until the bucket empties, then `429`. Doing this poisons the public bucket for
  about a minute, including for sign-in — run it last, or not at all.

## Gotchas

- **A `429` reads exactly like a broken endpoint.** Public is 10/min per IP, writes 60/min, AI
  20/min, magic links 200/day, webhooks 600/min. The counters are per-JVM and in memory
  (`gastos.ratelimit.redis.enabled=false`), so every run against one instance shares them and a
  restart clears them.
- **Scoping refuses with `404`, not `403`.** A test that expects `403` for another account's row will
  fail against a correctly scoped API.
- **`MONETIZATION_ENFORCE=false` locally means plan gates are off.** A `403` with a plan-gate body is
  the gate working after someone set it `true`; it is not an authorization defect. The demo account's
  entitlements read `PREMIUM` / `ACTIVE`.
- **Three service methods use unscoped `findById` on purpose**, in admin-protected or
  admin-conditional paths. Do not "fix" one into a scoped lookup without reading the comment; they
  are cross-referenced to Linear issues in the code.
- **`X-Forwarded-For` is only trusted from a configured trusted proxy** since TEN-425. A spoofed
  header no longer moves the rate-limit bucket, and an integration test that relied on the old
  behavior has to name `127.0.0.1` as trusted.
- **Swagger UI cannot exercise any of this.** The spec publishes no security scheme, so every
  protected path answers `401` in the browser (TEN-202) — tooling, not the API.
- **The admin credentials are production-grade secrets even locally.** They grant `ROLE_ADMIN`,
  bypass the AI quota and unlock `/admin/**`. Never print them, never save a response containing
  them, never commit them.
