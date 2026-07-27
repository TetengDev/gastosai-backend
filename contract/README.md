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

Automatic. `.github/workflows/publish-contract.yml` runs on a `v*` tag: it boots the app
against a Postgres service container, generates the spec, sets this package's version from
the tag, and publishes to GitHub Packages using `PACKAGE_TOKEN`.

Never `npm publish` by hand — a spec that did not come from a tagged build has no
corresponding deployed backend.
