# Authentication

A client signs in with an email and a password and receives a JWT plus the profile fields it needs
to render a shell — name, nickname, avatar colour, default category, role, and whether this is a
first login. Every other endpoint in the product is closed until that token exists, and the same
four sign-in routes exist on both the v1 and v2 surfaces.

## Sub-features

- `login-v2` — `POST /api/v2/auth/login` returns `200` with a token for a seeded account.
- `login-v1` — `POST /auth/login` does the same on the unprefixed surface, for clients still pinned
  to v1.
- `login-reject` — a wrong password is `401`, and the body names no field that would confirm the
  email exists.
- `register` — `POST /api/v2/auth/register` creates an account and returns a token with
  `firstLogin: true`.
- `register-validate` — a password under 6 characters, a malformed email, or a blank name is `400`.
- `magic-link` — `POST /api/v2/auth/magic-link` accepts an email and returns a boolean map without
  revealing whether the account exists; `POST /api/v2/auth/magic-link/verify` exchanges the token.
- `token-usable` — the returned token authenticates a protected call, and its claims scope reads to
  that user only.

## How to get to it (user POV)

- The web sign-in form (`gastosai-web` `/login`) → `POST /api/v2/auth/login`.
- The mobile sign-in screen → the same route, through `src/api/client.ts`.
- The web registration form (`/register`) → `POST /api/v2/auth/register`, which also arms the
  first-run tour in the client.
- The magic-link form on `/register` → `POST /api/v2/auth/magic-link`, then the emailed link lands on
  `/auth/verify` → `POST /api/v2/auth/magic-link/verify`.
- Google sign-in → `POST /api/v2/auth/google`, which needs a real Google credential and is not
  drivable locally.

## Driving it with api.py

Preconditions: doctor passes; `S=.claude/skills/verify-gastosai-backend/api.py`;
`D=~/.claude/gastosai-backend-verify/$(date +%F)`; `RID=$(python3 $S runid)`.

- **Sign in on v2 (`login-v2`)** — the call the clients make:

  ```bash
  python3 $S req POST /api/v2/auth/login --anon --expect 200 --save $D/login-v2.json \
      --json '{"email":"demo@gastosai.dev","password":"demo123"}'
  ```

  Observable: `200`, `"token"` non-null, `"email": "demo@gastosai.dev"`, `"role": "USER"`.
  **Redact the token before attaching `login-v2.json`** — it is a live credential.

- **Sign in on v1 (`login-v1`)** — same body, unprefixed path:

  ```bash
  python3 $S req POST /auth/login --anon --expect 200 \
      --json '{"email":"demo@gastosai.dev","password":"demo123"}'
  ```

  Observable: `200` with the same record shape. v1 and v2 are separate claims; a green v2 login is
  not evidence about v1.

- **Reject a wrong password (`login-reject`)**:

  ```bash
  python3 $S req POST /api/v2/auth/login --anon --expect 401 \
      --json '{"email":"demo@gastosai.dev","password":"wrong"}'
  ```

  Observable: `401`. Read the body and confirm it does not distinguish "no such user" from "wrong
  password" — that distinction is the enumeration leak this check exists to catch.

- **Register a fresh account (`register`)** — stamp the run id into the address so the sweep and a
  human can both tell it apart:

  ```bash
  python3 $S req POST /api/v2/auth/register --anon --expect 200 --save $D/register.json \
      --json "{\"name\":\"Verify $RID\",\"email\":\"verify-$RID@gastosai.dev\",\"password\":\"verify123\"}"
  ```

  Observable: a token, `"firstLogin": true`, and the email echoed back. There is **no delete-user
  endpoint**, so this row outlives the sweep — see Gotchas before running it.

- **Reject a weak password (`register-validate`)**:

  ```bash
  python3 $S req POST /api/v2/auth/register --anon --expect 400 \
      --json "{\"name\":\"Verify $RID\",\"email\":\"short-$RID@gastosai.dev\",\"password\":\"12345\"}"
  ```

  Observable: `400` naming `password`; `RegisterRequest` bounds it at 6–72 characters.

- **Request a magic link (`magic-link`)**:

  ```bash
  python3 $S req POST /api/v2/auth/magic-link --anon --expect 200 \
      --json '{"email":"demo@gastosai.dev"}'
  ```

  Observable: `200` with a boolean map, and the same `200` for an address that does not exist — the
  response must not reveal which. With `MAIL_HOST` blank the link is written to the backend log
  (`/tmp/gastosai-verify/backend.log` when the workspace started it), which is where to read the
  token for the `verify` half.

- **Prove the token works (`token-usable`)**:

  ```bash
  python3 $S login                                        # caches it 0600, prints nothing
  python3 $S req GET /api/v2/user/entitlements --expect 200
  ```

  Observable: `200` for this account's own entitlements. Pair it with the anonymous refusal in
  [authorization.md](./authorization.md); a token that works proves less than a missing token failing.

## Gotchas

- **`429` is the rate limiter, not a credentials failure.** All of `/auth/**` is public and the
  public bucket is 10 requests/minute per IP. Running the login checks above back to back is close
  to that ceiling; `api.py login` calls it out explicitly. Magic links have their own daily cap of
  200.
- **Registration leaves a permanent row.** There is no user-delete endpoint, and `sweep` does not
  touch accounts. Run `register` only when the claim needs it, keep the run id in the address, and
  say in the proof that the account remains.
- **The demo password comes from backend startup, not from `.env` as it currently reads.** The
  loader sets a password only when it creates the user, so a `401` on a correct-looking password
  means the row predates the current `.env`. Restarting the API does not fix that one; the stored
  hash has to change.
- **`POST /api/v2/auth/google` is not drivable locally** — it wants a real Google ID token. Do not
  report it as broken; report it as unreachable.
- **Never paste a token into evidence.** `api.py` caches it at `0600` and prints only its length;
  a `--save` of a login response contains the real thing, so redact that file before attaching it.
- **`/user` (unprefixed, singular) answers `500`** and never reaches a controller. The profile
  routes are `/api/v2/user/**`. Recorded in `../../../docs/security-pentest-2026-09-24.md`.
