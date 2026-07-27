# CONTRACT.md — the gastosai API contract (polyrepo)

Read this before touching any repo. It defines the one rule that keeps three
independent repositories from drifting apart: **the backend publishes the API
contract as a versioned package, and every client pins and generates from it.**

This is the polyrepo version of the contract. Unlike a monorepo — where the spec is
a shared file both sides can see — here the coordination is gone, so the contract
must be an explicit, versioned, published artifact. Keep this file's copy identical
in the backend and web repos.

---

## The repos

| Repo | Role |
|---|---|
| `gastosai-backend` | Spring Boot 4 / Java 25. **Owns and publishes** the contract. |
| `gastosai-web` | React 19 + Vite. **Pins and consumes** the contract. |
| `gastosai-mobile` | React Native (later). **Pins and consumes** the contract. |

Independent repos, independent CI, independent deploys. The only thing binding them
is the published contract below.

---

## The one rule

**The backend generates `openapi.json` (springdoc) and publishes it as the versioned
npm package `@tetengdev/gastosai-api-contract` on GitHub Packages. Clients depend on
an exact pinned version and generate their typed client from it. No client ever
hand-writes a request/response type, and no client generates from a live URL.**

Why published-and-pinned, not a shared folder: the repos no longer share a
filesystem. A pinned package version is what replaces that shared visibility. A
client upgrades the contract deliberately, regenerates, sees the type errors, and
migrates — drift becomes a visible, versioned event instead of a silent runtime break.

---

## The workflow

```
  gastosai-backend
    OpenApiContractTest → contract/openapi.json (npm package)
        │  publish on contract-v* tag
        ▼
  GitHub Packages: @tetengdev/gastosai-api-contract@X.Y.Z
        │  pinned dependency
        ├───────────────────────────┐
        ▼                           ▼
  gastosai-web                 gastosai-mobile
  openapi-typescript           openapi-typescript
  → src/api/generated/         → src/api/generated/
```

1. Backend defines endpoints; springdoc generates the spec, written by a test in the
   normal `./mvnw test` run. Backend CI fails if the committed spec is stale.
2. On a `contract-v*` tag, the backend publishes the contract package with a semver version.
3. Each client pins an **exact** version (no `^`, no `~`) and runs `openapi-typescript`
   against the installed package into `src/api/generated/` — never hand-edited.
4. Each client's CI regenerates and fails if the committed generated code is stale
   against the pinned version, then type-checks. That failure is the drift guard.

**The contract version is not the application version.** The app tags `v*` (currently `0.x`);
the contract tags `contract-v*` and starts at `1.0.0`. Most app releases do not change the API
surface — republishing an unchanged spec under a new number would make the pin meaningless.

---

## Versioning — contract version = API compatibility

- **Non-breaking** (new optional field, new endpoint) → **minor** bump. Clients pick
  it up when they choose to upgrade the pin.
- **Breaking** (removed/renamed field, changed type, tightened validation, removed
  endpoint, a request field becoming required) → **major** bump **and** a new URL version
  path `/api/v2`. The old version path stays live until every client has migrated.
- **Mobile is the pacing constraint.** Installed apps run old versions for months.
  Never remove a `/api/v1` endpoint until analytics show old app versions have
  drained. This is the single strongest reason breaking changes are additive-first.

---

## Cross-repo change ordering

A change that spans the contract is **not** one commit anymore — it's an ordered
sequence across repos. Do it in this order, every time:

1. Backend: implement the change following expand-contract (add new shape first).
2. Backend: publish a new contract version (minor for additive, major for breaking).
3. Clients: bump the pinned version, regenerate, fix type errors, migrate.
4. Backend: only after clients have migrated, publish the contract-removal (contract
   step) that drops the old shape — a later major version.

Never publish a breaking contract version before clients have a migration path.

---

## Cross-cutting data rules (identical in all repos)

- **Money is never floating point.** Currency explicit, default `PHP`. Format to
  `₱1,234.56` only at the display edge. See `KNOWN-GAPS.md` — the API currently serves
  decimal amounts at full precision; the integer-centavos target is a future breaking change.
- **Timestamps are ISO 8601 with `+08:00`.** Store UTC, serialize with offset.
  Day/month logic in `Asia/Manila`. A naive timestamp is a bug.
- **No AI provider key ever reaches a client.** AI runs backend-only.
- **Business logic lives only in the backend.** Clients render and send; they never
  compute totals, budgets, or categorization.

---

## Auth

Application auth is a **backend-issued JWT** (`jjwt`), sent as a bearer token; Supabase is
the managed Postgres host only, and there is no Supabase client SDK or anon key in any
client. If that ever changes, update this section in both repos.

## Auth for the contract package

Both publishing (backend) and installing (web, mobile, Vercel) use a GitHub
`PACKAGE_TOKEN` scoped to `@tetengdev` via `npm.pkg.github.com`, supplied as an env
var / CI secret. Never commit the token; never inline it in `.npmrc` — reference
`${PACKAGE_TOKEN}`.
