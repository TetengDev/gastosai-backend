# Deployed state — decisions about what production exposes

Decisions about what a production instance serves, why, and how to answer the questions the
decision makes harder. One section per decision. The authority for each is the property named
in it, not this file: if the two disagree, the property is what runs and this file is stale.

---

## OpenAPI / Swagger UI in production: **disabled**

**Decision.** A production instance serves neither the generated OpenAPI document nor the
Swagger UI. `src/main/resources/application-prod.properties`:

```properties
springdoc.api-docs.enabled=false
springdoc.swagger-ui.enabled=false
```

Both are on by default under springdoc, and the `prod` profile is the only place they are turned
off — so this holds exactly when `SPRING_PROFILES_ACTIVE=prod`, which `compose.prod.yml` sets for
the `api` service. Locally and in tests the profile is not active and both stay on: Swagger UI at
`/swagger-ui.html`, the document at `/v3/api-docs`.

**Reason.** The document is a complete map of every route, parameter, enum and error shape,
including the ones an unauthenticated caller cannot discover any other way — the auth, admin and
webhook surfaces among them. Publishing it turns endpoint discovery from work into a download
(CWE-200). Nothing in production needs it: clients do not read the spec at runtime, they generate
from `@tetengdev/gastosai-api-contract` at build time, so the live document has no consumer other
than someone probing the service.

The cost of the decision is real but small, and it is paid once: the deployed API surface is no
longer readable from the deployment. The next section is how to get it anyway.

**What a caller actually sees.** Verified against a local `prod`-profile boot (v0.67.2, scratch
database, `:8099`): `/v3/api-docs`, `/swagger-ui.html` and `/swagger-ui/index.html` all answer
`500` with the generic problem-detail body, not `404`. The springdoc paths are permitted by the
security config, so the request gets past authentication and then finds no handler bean behind
them; an unmatched route that is *not* permitted answers `401` instead. Either way nothing about
the API surface is disclosed — `server.error.include-stacktrace=never` keeps the body empty of
detail. The status code is untidy rather than unsafe; making these `404` would mean changing the
security config, which is outside this decision's scope.

### Getting the deployed surface, for diffing

The spec is not lost — it is versioned in the repo rather than served by the instance.
`OpenApiContractTest` writes `contract/openapi.json` from the live route table on every
`./mvnw test`, key-sorted and stable, and CI fails when the committed copy is stale. So the
document that *would* have been served at `/v3/api-docs` by a given build is the
`contract/openapi.json` committed at that build's commit.

Two steps, then, to diff production against anything:

**1. Identify what is deployed.** `/actuator/info` is exposed in production
(`management.endpoints.web.exposure.include=health,info`) and carries the build version — the
Maven `build-info` goal bakes it into the image. Fetch `https://<host>/actuator/info` from a
browser or any HTTP client that can leave the machine; the repo's `scripts/http_check.py` helper
reaches loopback only, so it is for the local step below, not this one.

Cross-check against `IMAGE_TAG` in `.env.prod` on the VM — the tag names exactly one build, and
`build-image.yml` tags every image with both the application version and the commit SHA, so
either form of the tag resolves to a commit.

**2. Read the spec at that commit.**

```bash
git show v<version>:contract/openapi.json          # or the commit SHA from the image tag
git diff v<previous>:contract/openapi.json v<version>:contract/openapi.json
```

For a client-facing diff, compare the published package versions instead — the contract is
tagged `contract-v*` and versioned independently of the application (`CONTRACT.md`), so two app
releases usually pin the same contract.

If you need the rendered UI against a real running build rather than the file, run the same
pinned image locally **without** the `prod` profile — it is the identical artifact, so its
document is byte-identical to the deployed one, and it is reachable only on loopback:

```bash
docker run --rm -p 8080:8080 ghcr.io/tetengdev/gastosai-backend:<IMAGE_TAG> \
  --spring.profiles.active=default --spring.datasource.url=<a scratch database>
python3 ../scripts/http_check.py http://localhost:8080/v3/api-docs
```

**What is not an answer:** temporarily flipping `springdoc.api-docs.enabled=true` on the
production instance to read the spec. It exposes the full surface for the length of the window
and to everyone, and it produces nothing that the committed file does not already give you.

---

## Related

- `CONTRACT.md` — versioning and compatibility rules for the published contract.
- `contract/README.md` — the version-bump rule and how the package is published.
- `docs/deploy-oracle.md` — how a production instance is stood up and what `IMAGE_TAG` pins.
