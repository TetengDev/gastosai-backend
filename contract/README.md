# @tetengdev/gastosai-api-contract

The gastosai API contract, published from this repo to GitHub Packages. `openapi.json` is
**generated** by springdoc from the live controller surface — never hand-edited.

Clients (`gastosai-web`, later `gastosai-mobile`) depend on an **exact** version and run
`openapi-typescript` against it. See `../CONTRACT.md` for the full rules.

## Regenerating

`OpenApiContractTest` generates this file as part of the normal test run — no database, no
separate process, no extra flags:

```bash
./mvnw test
```

The same test asserts every `@RestController` endpoint appears in the spec, so a controller
springdoc silently skips fails the build rather than shipping a contract clients cannot call.
Output is key-sorted and indented, so regeneration is byte-identical unless the API actually
changed and `git diff contract/openapi.json` is a true signal.

CI fails if the committed spec is stale against the code.

## The version-bump rule

**The contract version is a statement about API compatibility.** Bump it by what the spec
change does to a client that has already generated code from the previous version.

| Change | Bump | Also required |
|---|---|---|
| New endpoint | **minor** | — |
| New optional response field | **minor** | — |
| New optional request field | **minor** | — |
| Removed or renamed field | **major** | new `/api/v2` path; keep `/api/v1` live |
| Changed field type | **major** | new `/api/v2` path; keep `/api/v1` live |
| Tightened validation (a previously accepted request now 400s) | **major** | new `/api/v2` path; keep `/api/v1` live |
| Removed endpoint | **major** | new `/api/v2` path; keep `/api/v1` live |
| A field becoming required in a request | **major** | new `/api/v2` path; keep `/api/v1` live |

A breaking change is never published alone. Follow expand-contract:

1. Backend: add the new shape alongside the old one → publish a **minor**.
2. Clients: bump the pin, regenerate, migrate to the new shape.
3. Backend: only once every client has migrated, remove the old shape → publish the **major**.

**Mobile paces this.** Installed apps run old versions for months, so a `/api/v1` endpoint
stays live until analytics show those versions have drained — not until web has migrated.

## Publishing

**Bumping `version` in this file's `package.json` is the whole action.** Merge that bump to
`main` and `.github/workflows/publish-contract.yml` runs the suite, re-verifies the spec is
current, publishes to GitHub Packages, and then cuts the `contract-v<version>` tag itself. There
is no separate step to remember, because the version field is the trigger.

The tag is the **receipt** of a publish, not its trigger. That inversion is the mechanism, and
it is worth the paragraph:

Until TEN-345 the workflow ran only `on: push: tags: contract-v*`. A bump that merged without
someone cutting the tag by hand did not fail — the workflow simply never ran. Ten of them landed
that way between 2026-07-28 and 2026-09-04 (1.2.0 through 2.10.0), in commits whose messages said
"publish", and none were published. Nothing went red, because *nothing ran*; a missing trigger has
no failure output. The symptom surfaced five weeks and forty commits later at the other end of the
system, as an `E401`/`404` from GitHub Packages when a client tried to pin a version that did not
exist. TEN-315 sat blocked on exactly that, and finding the cause took a full session.

A green CI run was never evidence that a contract had been published. Now it is, because the
publish is on the same path as the merge instead of beside it.

Two guards keep that path honest:

- **On the PR** — `continuous-integration.yml`'s *Contract version is publishable* job. It is
  silent when the version is unchanged, which is nearly every PR. When the version does move it
  rejects the two bumps the release lane would have to skip: a version that already has a
  `contract-v*` tag (the registry would reject the republish), and a version that moves backwards
  (clients pin exact versions, so a reused number changes what an existing pin resolves to). A
  skipped publish is the original silent failure wearing a different hat, so it is caught while
  the diff is still in front of a reviewer.
- **On `main`** — the release lane re-checks the same tag condition before publishing, and only
  tags after `npm publish` returns. A tag that exists without a package behind it is the failure
  in mirror image and the ordering rules it out.

The manual lane still exists: pushing a `contract-v*` tag by hand publishes that tag's version.
It is for backfilling a commit that predates this mechanism, not for ordinary work. It stays
reachable because the automatic lane pushes its tag with the workflow's `GITHUB_TOKEN`, and GitHub
deliberately does not re-trigger workflows from such a push — so the automatic lane cannot loop,
and a human keeps a way in.

Publishing authenticates with the workflow's built-in `GITHUB_TOKEN` (`packages: write`), not
a stored PAT — it is scoped to a single run and needs no rotation. That works because the
package and this repo share the `TetengDev` owner. **Consumers** still need a token with
`read:packages`, because they install from a different repo.

Never `npm publish` by hand — a spec that did not come from a green CI build on `main` has no
corresponding deployed backend.
