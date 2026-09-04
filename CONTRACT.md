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
- **"Changed type" on an output field means narrowed, not widened.** Widening an output
  type to admit a value the wire already produced (e.g. `string` → `["string","null"]`
  when the field could always come back null) is a **minor** bump — the server is
  promising less than before, not more, and no client that already handled the old
  type can be surprised by the new one. Narrowing an output type (e.g. dropping `null`
  from the union, or `number` → `integer`) is still **major**: it forbids a value the
  client may already be receiving. This applies to output/response types only — a
  request field's accepted type narrowing or widening follows the general rule above,
  since the server is the one that has to handle whatever the client sends.
- **Mobile is the pacing constraint.** Installed apps run old versions for months.
  Never remove a `/api/v1` endpoint until analytics show old app versions have
  drained. This is the single strongest reason breaking changes are additive-first.

---

## The 2.0.0 break — integer centavos on `/api/v2`

*Recorded 2026-08-19 (TEN-136). The change itself shipped in TEN-135.*

**What changed.** Every money-bearing field on `/api/v2` is an integer number of centavos
(`long`, e.g. `amountLimit: 190000` for ₱1,900.00). On `/api/v1` money remains a decimal
`number` and is **byte-identical to before** — same code path, same rows.

**Why it is a major.** The type of an existing field changed. That is breaking by the rule above,
even though nothing was removed, so it required a major *and* a new URL path with the old one kept
live. Both surfaces read the same rows; conversion happens in the v2 DTO factory, so there is no
second source of truth.

**Migration order for clients.** Do not treat this as urgent — nothing on `/api/v1` has changed,
and there is no deadline attached to it:

1. Bump the pin to the published 2.x contract and `npm run gen:api`.
2. Fix the type errors. They are the safety net: every money field that moves from a decimal to an
   integer will fail to compile until it is handled.
3. Divide by 100 **only at the display edge**, through `src/lib/formatters.ts`. Never do arithmetic
   on the converted value — the integer is the money; the decimal is a rendering of it.
4. Repoint calls from `/api/…` to `/api/v2/…`.

**Web may migrate before mobile, and should.** Mobile is the pacing constraint (installed apps call
`/api/v1` for months), so the two clients are deliberately allowed to sit on different major
versions. `/api/v1` is retired only when analytics show old app versions have drained — a later
issue, not a consequence of this one.

**Publishing is a manual step and has been missed once.** `contract/package.json` said `2.0.0`
from the day `/api/v2` shipped, but `publish-contract.yml` fires on a `contract-v*` **tag** and
nothing had tagged it — so for several days the entire v2 surface existed in the spec and in the
running API, and in no package a client could pin. Bumping the version in the file publishes
nothing. See TEN-271.

---

## Recorded exception — `/api/v2/ai/chat` centavos narrowing without a new path

*Recorded 2026-09-03 (TEN-336). The change itself shipped in TEN-308 (PR #94), contract
`2.10.0` → `3.0.0`.*

This is an exception to the rule above, not a softening of it: it took a major bump but did
**not** get a new URL version path, because `/api/v3` did not need to exist for this change to
be safe.

**What changed.** `POST /api/v2/ai/chat` had been serving the v1 `ChatResponse` shape — decimal
money, unconverted. TEN-308 made it serve `ChatResponseV2`, narrowing every money-bearing field
from decimal to integer centavos. A response type narrowing is breaking by the rule above, so it
took the major bump (`3.0.0`).

**Why no new path.** The "keep the old path live" clause exists so a client depending on the old
shape keeps working. That guarantee already held without a new path: the decimal shape stays
served, byte-identical, at v1's `POST /ai/chat` — a different route, not a version of this one.
Publishing `/api/v3/ai/chat` to carry the new shape would have meant standing up a version whose
only content is "this one endpoint now does what `/api/v2` was always supposed to do" — see the
next paragraph.

**What made it safe.** No shipped client called `/api/v2/ai/chat`: `gastosai-web/src/api/ai.ts`
and `gastosai-mobile/src/api/chat.ts` both call the unversioned v1 path, and neither repo
referenced `/api/v2/ai/chat` or a `v2AiChat` client method. There was nothing to break. `/api/v2`
itself was created by TEN-135 specifically to promise integer centavos from `2.0.0` onward; this
endpoint had never honoured that promise. TEN-308 corrected `/api/v2/ai/chat` to match the
version it was already published under, rather than freezing the gap into a new major.

**Do not read this as license to skip the new-path step generally.** It applied here only because
both preconditions held at once: zero consumers of the narrowing endpoint, and the pre-existing
decimal shape already living safely at a different, unaffected path. A breaking change with an
actual consumer, or one with no fallback path already serving the old shape, still needs
`/api/v(n+1)` per the rule above.

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
