# Architecture — gastosai backend (polyrepo, published contract)

How the system is put together after the monorepo split. For the rules that govern
cross-repo change, read `CONTRACT.md`; for what the code does not yet match, `KNOWN-GAPS.md`.

---

## 1. Topology

Three independent repositories, independently built and deployed, bound by one published
artifact:

```
                    ┌──────────────────────────────────────┐
                    │        gastosai-backend              │
                    │  Spring Boot 4.1 / Java 25 / Maven   │
                    │                                      │
                    │  OpenApiContractTest                 │
                    │    → contract/openapi.json           │
                    └──────────────┬───────────────────────┘
                                   │ contract-v* tag
                                   ▼
                 GitHub Packages: @tetengdev/gastosai-api-contract@X.Y.Z
                                   │
                    exact pinned dependency (no ^, no ~)
                     ┌─────────────┴─────────────┐
                     ▼                           ▼
            ┌─────────────────┐        ┌─────────────────────┐
            │  gastosai-web   │        │  gastosai-mobile    │
            │  React 19/Vite  │        │  React Native       │
            │  → Vercel       │        │  (planned)          │
            └────────┬────────┘        └──────────┬──────────┘
                     │      HTTPS + Bearer JWT    │
                     └────────────┬───────────────┘
                                  ▼
                    ┌──────────────────────────────┐
                    │  Oracle Cloud Always Free    │
                    │  Ampere A1 (ARM64), SG       │
                    │  Caddy (TLS) → api :8080     │
                    │  image: ghcr.io/... :TAG     │
                    └──────────────┬───────────────┘
                                   ▼
                       Supabase PostgreSQL 17
                       (schema owned by Flyway)
```

**What replaced the monorepo's coordination.** In one repo, a controller change and its client
call site were one commit, and the compiler saw both. Here they are separate repositories with
separate CI. The published contract is what stands in for that shared visibility: the backend
cannot change its API without producing a new `openapi.json`, and no client can drift from what
it pinned without its CI failing. Drift becomes a versioned, visible event instead of a runtime
surprise in production.

---

## 2. Request flow

```
Controller → Service → Repository → PostgreSQL
```

Business logic lives in the service/domain layer; controllers translate HTTP to DTOs and back
and never compute anything.

The AI query path deviates deliberately:

```
AiController → AiQueryService → SqlGenerator (OpenAI | Claude)
             → SqlGuard  ← the security boundary
             → raw JDBC (read-only) → formatted response
```

`SqlGenerator` is a port with two adapters, selected at runtime by `GASTOS_AI_PROVIDER` via
`AIClientConfig`. No provider SDK type crosses into the domain, and no provider key is ever
reachable by a client — all AI calls originate server-side.

**`SqlGuard` (`ai/SqlGuard.java`) validates every generated statement before execution:**
blocks non-`SELECT` statements, requires `FROM expenses`, rejects multi-statement input, and
blocks system catalog access (`pg_`, `information_schema`). Never bypass or weaken it.

---

## 3. The contract, concretely

`OpenApiContractTest` runs in the ordinary test phase, calls `/v3/api-docs` through MockMvc,
and writes `contract/openapi.json`. Three properties make this the source of truth rather than
a snapshot that rots:

1. **Completeness** — the test fails if any `@RestController` endpoint is missing from the spec.
2. **Determinism** — output is key-sorted and indented, so an unrelated rebuild produces a
   byte-identical file and `git diff` only ever shows a real surface change.
3. **Freshness** — CI fails if the committed spec differs from a fresh generation, so a
   controller change cannot merge without its contract update.

Publishing is a separate, deliberate act: a `contract-v*` tag runs the suite, re-verifies the
spec, and `npm publish`es `contract/`. The contract version tracks **API compatibility**, not
the application version — see `CONTRACT.md`.

---

## 4. Schema and migrations

Flyway owns the schema; Hibernate runs with `ddl-auto=validate` and will refuse to start if the
entities and the migrated schema disagree. Migrations are append-only — an applied migration is
never edited.

All schema change is **expand-contract**:

| Step | What it does | When the next step is safe |
|---|---|---|
| expand | add the new column/table alongside the old | immediately |
| backfill | populate the new shape | once expand is deployed everywhere |
| contract | drop the old shape | once no deployed code reads it |

A single-step rename or retype is prohibited: deployed instances read the old shape until the
new build has fully rolled out.

`scripts/backup-before-migrate.sh` (and the `.ps1` twin) runs before Flyway in the deploy path
and **fails closed** — it verifies the dump is non-trivial and passes a gzip integrity check,
and deletes any partial file. No restorable backup, no migration: a contract-step migration is
the one thing a rollback cannot undo.

---

## 5. Deploy and rollback

| Layer | Where | Notes |
|---|---|---|
| Backend | Oracle Cloud Always Free (Ampere A1, Singapore) | `compose.prod.yml` + Caddy for automatic HTTPS |
| Web | Vercel | `VITE_API_URL` points at the backend |
| Database | Supabase PostgreSQL 17 | free tier pauses after ~1 week idle |

`build-image.yml` pushes `ghcr.io/tetengdev/gastosai-backend` tagged with **the application
version and the git SHA — never `:latest`**. `compose.prod.yml` references
`${IMAGE_TAG:?...}`, so the compose file refuses to start without an explicit tag.

Rollback is therefore: set `IMAGE_TAG` in `.env.prod` to the previous version and
`docker compose -f compose.prod.yml --env-file .env.prod up -d`. No rebuild on the VM, and no
ambiguity about what is running. The image is private, so the VM needs `docker login ghcr.io`
once with a `read:packages` token — see `docs/deploy-oracle.md`.

Vercel rolls back by promoting a previous deployment.

---

## 6. Where things live

| Concern | Location |
|---|---|
| HTTP surface | `src/main/java/com/teng/app/gastosai/controller/` |
| Domain + services | `.../service/`, `.../domain/` |
| AI adapters and the guard | `.../ai/` |
| Persistence | `.../entity/`, `.../repository/` |
| Security / JWT | `.../config/SecurityConfig.java`, `.../config/JwtUtil.java` |
| Schema | `src/main/resources/db/migration/` |
| Published contract | `contract/` |
| Ops scripts | `scripts/` |
