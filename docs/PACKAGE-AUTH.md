# Package authentication runbook

How each consumer of `@tetengdev/gastosai-api-contract` authenticates to GitHub Packages,
and how to set it up. Read `CONTRACT.md` first for what the package *is*.

The guiding rule: **every consumer uses the weakest credential that works.** A long-lived
personal access token is the last resort, not the default — it outlives the job that needed
it, it carries its creator's identity, and it has to be rotated by a human who will forget.

---

## Who needs what

| Consumer | Action | Credential | Stored secret? |
|---|---|---|---|
| `gastosai-backend` CI | **publish** | built-in `GITHUB_TOKEN` (`packages: write`) | none |
| `gastosai-web` CI | install | built-in `GITHUB_TOKEN`, once the repo is granted package access | none |
| Vercel | install | classic PAT, `read:packages` | `PACKAGE_TOKEN` |
| Local dev machine | install | classic PAT, `read:packages` | `.env` (gitignored) |

Publishing needs no PAT because the package and the publishing repo share the `TetengDev`
owner — GitHub grants a run-scoped token write access to its own org's packages. That token
exists only for the duration of one workflow run.

Only the two consumers **outside** GitHub Actions genuinely need a durable token.

---

## Step 1 — Give `gastosai-web` CI access to the package

Do this before creating any token; it removes the need for one in CI entirely.

1. Go to the package:
   `https://github.com/orgs/TetengDev/packages/npm/package/gastosai-api-contract`
2. **Package settings** → **Manage Actions access**
3. **Add repository** → `gastosai-web` → role **Read**

The web workflow can now install with its own `GITHUB_TOKEN`. Nothing to rotate, nothing to
leak, and access is visible in one place rather than implied by a secret's contents.

> Not yet applied — `.github/workflows/continuous-integration.yml` in `gastosai-web` still
> passes `PACKAGE_TOKEN`. After granting access, change those `PACKAGE_TOKEN:` env lines to
> `${{ secrets.GITHUB_TOKEN }}` and delete the repo secret.

---

## Step 2 — Create the PAT (Vercel and local dev only)

**Classic**, not fine-grained. GitHub Packages' npm registry does not properly support
fine-grained tokens — they return `403 Resource not accessible by personal access token`
even with the right permissions selected. This is the one place classic tokens are still
required.

1. https://github.com/settings/tokens/new
2. Note: `gastosai contract install`
3. Expiry: **90 days** (see rotation below)
4. Scope: **`read:packages`** and nothing else
   - Not `write:packages` — nothing outside CI should publish.
   - Not `repo` — installing a package does not require source access.
5. **Generate token**, copy it once
6. On the token list, next to `TetengDev`: **Configure SSO → Authorize**

Step 6 is the one people miss. `TetengDev` is an Organization; an unauthorized token fails
with a permissions error that looks exactly like a wrong scope.

---

## Step 3 — Store it

**Vercel** (Project → Settings → Environment Variables):

| Variable | Value | Environments |
|---|---|---|
| `PACKAGE_TOKEN` | the token | Production, Preview, Development |
| `VITE_API_URL` | backend HTTPS URL | Production, Preview |
| `VITE_BILLING_ENABLED` | `false` for free-launch, or omit | as needed |

**Local** — append to `gastosai-web/.env` (gitignored; verify with `git check-ignore .env`):

```
PACKAGE_TOKEN=ghp_...
```

Never paste a token into `.npmrc`. Both repos' `.npmrc` reference `${PACKAGE_TOKEN}` /
`${NODE_AUTH_TOKEN}` precisely so the value stays out of version control.

**If you skip Step 1** and want CI to use the PAT instead:

```bash
gh secret set PACKAGE_TOKEN -R TetengDev/gastosai-web
```

This prompts for the value rather than taking it as an argument, so the token never enters
your shell history.

---

## Step 4 — Verify

```bash
# The registry accepts the token
npm view @tetengdev/gastosai-api-contract version \
  --registry=https://npm.pkg.github.com \
  --//npm.pkg.github.com/:_authToken=$PACKAGE_TOKEN
# -> 1.0.0

# A clean install resolves it
cd gastosai-web && rm -rf node_modules && npm install && npm run gen:api
git status --porcelain src/api/generated   # empty = generated client matches the pin
```

An empty `git status` there is the whole point of the contract: the committed client and the
pinned contract agree.

---

## Rotation

The token is read-only, so rotation is low-stakes — but it *will* expire, and it will do so
by failing a Vercel build with a confusing `E401` rather than an obvious message.

- Set a calendar reminder for ~1 week before expiry.
- Rotating means: create the new token, authorize SSO, update Vercel and your local `.env`,
  then delete the old one. No code change.
- If you complete Step 1, CI is unaffected by expiry — only Vercel and local installs break,
  which fail loudly and immediately rather than silently.

**If a token leaks:** revoke it at https://github.com/settings/tokens immediately. Because the
scope is `read:packages` only, the blast radius is read access to private packages — no source
access, no publish rights, no ability to alter a released contract.

---

## Why not other approaches

- **`GITHUB_TOKEN` for Vercel** — impossible; it exists only inside a GitHub Actions run.
- **Making the package public** — would remove the install token everywhere, and is the one
  option worth arguing about. Rejected anyway.

  The spec itself is no longer the issue: both repos are public, so `contract/openapi.json` is
  already readable by anyone. The objection is to *packaging* it for public consumption. This
  package is an internal coupling mechanism between two repos we own — not a library. Publishing
  it makes it discoverable and effectively permanent: others can depend on it, and renaming or
  unpublishing later stops being purely our decision. In exchange it removes a `read:packages`
  token, which is deliberately the weakest credential in the system — it cannot publish, cannot
  read source, expires in 90 days, and rotating it is a calendar reminder rather than an outage.

  A permanent public artifact is a poor trade for removing the cheapest possible secret. If the
  token is the real irritant, do Step 1 instead: it removes the secret from CI while the package
  stays private.

  (`TetengDev` also disables public-package visibility at the org level. That is a policy an org
  admin can lift — but the reasoning above is why it should stay as it is.)
- **Committing `openapi.json` into the web repo** — recreates the shared-file coupling the
  polyrepo split exists to remove. The pinned package version is the whole mechanism.
- **A fine-grained PAT** — see Step 2; the registry rejects them.
