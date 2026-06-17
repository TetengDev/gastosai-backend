# Observability — structured logging

GastosAI emits **structured JSON logs** in production so they can be searched and
filtered in a hosted log aggregator. Development keeps the normal human-readable
console output.

## How it works

- **Format:** Spring Boot 4's native structured logging is enabled in the `prod`
  profile (`application-prod.properties`) via `logging.structured.format.console=ecs`
  — every log line is one JSON object in [Elastic Common Schema](https://www.elastic.co/guide/en/ecs/current/index.html)
  (ECS), which Grafana Loki, Better Stack, Elastic, and most aggregators ingest
  directly. No extra dependency is required.
- **Correlation id + request context:** `RequestLoggingFilter` runs first in the
  security chain. For every request it:
  - reads an incoming `X-Request-Id` header or generates a UUID, puts it in the
    logging MDC as `requestId`, and echoes it back in the `X-Request-Id` response
    header (so a client/proxy id flows end-to-end);
  - on completion logs one `http_request` line carrying `requestId`, `method`,
    `path`, `status`, `durationMs`, and `userId` (the authenticated user's email,
    when present).
- **No secrets:** logs never include request bodies, headers, JWTs, API keys, or
  passwords — only the fields listed above. `/actuator/**` is skipped to cut noise.

Dev (default profile) is unaffected — you get the readable console logs as before.

## Verifying locally

```powershell
cd backend
$env:SPRING_PROFILES_ACTIVE = "prod"
.\mvnw.cmd spring-boot:run
```

Hit any endpoint and confirm each stdout line is JSON containing `requestId`,
`http.request.method`, `url.path`, `http.response.status_code`, and the MDC fields.
The HTTP response carries an `X-Request-Id` header. Unset `SPRING_PROFILES_ACTIVE`
to return to readable dev logs.

## Shipping logs to a free aggregator (Grafana Cloud Loki)

Because the app already writes ECS JSON to **stdout**, you don't need an in-app
network appender — point your host's log drain at the aggregator.

**Recommended: Grafana Cloud (free tier, no credit card).**

1. Create a free Grafana Cloud account → it provisions a **Loki** instance with a
   push URL, a user/tenant id, and an API token.
2. Forward the backend container's stdout to Loki using your host's log drain:
   - **Koyeb / Railway / Fly:** add a log drain / sink pointing at the Loki push
     endpoint with the token (no app change).
   - **Self-managed Docker:** run the Grafana **Alloy**/Promtail agent (or the
     Loki Docker logging driver) to tail the container stdout and push to Loki.
3. In Grafana Explore, query the stream and filter by the JSON fields, e.g.
   `{service_name="gastosai"} | json | status >= 500` or by `requestId` to trace a
   single request.

**Alternative:** Better Stack (Logs) free tier works the same way — create a source,
take its token, and point the host drain at its ingest endpoint.

> An always-on in-app HTTP appender is intentionally **not** wired up: it would need
> an account + token at build/runtime and would fail without one. The stdout-drain
> approach keeps the app host-agnostic. If a direct push appender is ever wanted,
> add an env-gated `logback-spring.xml` appender (e.g. `loki4j`) enabled only when
> `LOKI_URL`/token env vars are set.
