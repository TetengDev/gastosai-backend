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

---

# Admin dashboard — `/admin/observability`

Admin-only (JWT with `ROLE_ADMIN`). Aggregates the operational picture in-app so you
don't need an external tool for day-to-day checks. Four sections:

- **System** — database up/down, running version, uptime.
- **Users & activity** — total users, signups (today / 7d / 30d), active users (24h / 7d),
  top users by AI request count.
- **AI cost** — today's estimated USD spend, month-to-date by feature + model, success/failure counts.
- **Operational events** — server errors (5xx) and abuse-guard trips, read from the
  `app_event` table (persisted, so they survive Render free-tier sleep/restart). The raw
  exception `detail` is deliberately not surfaced in the UI.

Backing endpoints (all under `/admin/**`, admin-gated):
`GET /admin/observability/{summary,cost,events,health}`.

---

# Alerts

## In-app cost/abuse alerts (Telegram)

A scheduler evaluates thresholds every `ALERT_INTERVAL_MS` (default 15 min) and posts a
Telegram message when one is breached. Each condition is de-duplicated to a time window
(day or hour) so a sustained breach alerts once, not every tick.

| Condition | Default threshold | Window |
|---|---|---|
| Daily AI spend over budget | `ALERT_DAILY_COST_USD` = 5.00 | once/day |
| Global daily AI cap approaching | `ALERT_GLOBAL_CAP_FRACTION` = 0.9 of `AI_GLOBAL_DAILY_MAX` | once/day |
| Server-error spike | `ALERT_ERROR_RATE` = 20 errors/hour | once/hour |

**Disabled by default.** Alerts only fire when `ALERTS_ENABLED=true` **and** both
`TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` are set (the same bot used by the tooling
scripts). With any of those missing the scheduler is a no-op.

> **Caveat:** these checks run only while the instance is awake. On the Render free tier
> the backend sleeps after ~15 min idle, so this is **not** a downtime detector — cost and
> abuse accrue during active use, which is when the scheduler is running anyway. Use the
> external pinger below for uptime.

## External uptime — UptimeRobot (free)

1. Create a free account at <https://uptimerobot.com>.
2. Add a new **HTTP(s)** monitor:
   - URL: `https://<your-render-app>/actuator/health`
   - Interval: 5 minutes
3. Set an alert contact (email, or Telegram via UptimeRobot's integration).

`/actuator/health` is public and returns `{"status":"UP"}` when the app + database are
healthy (mail/Redis excluded so a best-effort dependency can't trigger a false alarm).
The 5-minute ping also keeps the free instance warm, cutting cold-start latency for real users.

## Alert environment variables

| Variable | Default | Purpose |
|---|---|---|
| `ALERTS_ENABLED` | `false` | Master switch for in-app Telegram alerts |
| `TELEGRAM_BOT_TOKEN` | — | Bot token (shared with tooling scripts) |
| `TELEGRAM_CHAT_ID` | — | Destination chat id |
| `ALERT_INTERVAL_MS` | `900000` | Scheduler evaluation interval (ms) |
| `ALERT_DAILY_COST_USD` | `5.00` | Daily AI-spend alert threshold (USD) |
| `ALERT_ERROR_RATE` | `20` | Server errors/hour before alerting |
| `ALERT_GLOBAL_CAP_FRACTION` | `0.9` | Fraction of `AI_GLOBAL_DAILY_MAX` before alerting |
