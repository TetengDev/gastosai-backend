# GastosAI — Backend

Spring Boot 4 / Java 25 REST API for the GastosAI expense tracker.

---

## Prerequisites

- Java 25 ([Temurin 25](https://adoptium.net))
- Docker Desktop (for local PostgreSQL)
- No Maven install needed — use the included wrapper

---

## Environment setup

Copy the example and fill in values:

```powershell
copy .env.example .env
```

| Variable | Required | Description |
|---|---|---|
| `DB_URL` | No | JDBC URL — defaults to `jdbc:postgresql://localhost:5433/gastos` |
| `DB_USERNAME` | No | Database user — defaults to `postgres` |
| `DB_PASSWORD` | No | Database password — defaults to `dev` |
| `DB_HOST_PORT` | No | Host port `docker-compose.yaml` publishes the database on — defaults to `5433` |
| `OPENAI_API_KEY` | One of these | OpenAI API key |
| `CLAUDE_API_KEY` | One of these | Anthropic API key |
| `GASTOS_AI_PROVIDER` | No | `openai` (default) or `claude` |
| `GASTOS_SEED_SAMPLE_DATA` | No | `true` seeds 15 sample expenses on first run |

The three `DB_*` defaults are the compose stack: `docker-compose.yaml` publishes `5433:5432`
with user `postgres` / password `dev`, so the local loop needs no `.env` at all. Set them only
when pointing at another database.

> The database must be running before starting the backend.
> Start it with `docker compose up -d --force-recreate` from the repo root.

---

## Running

```powershell
docker compose up -d --force-recreate
.\mvnw.cmd spring-boot:run
```

No `DB_URL` override is needed — the default already points at the port compose publishes.

`--force-recreate` matters: without it, `docker compose up -d` can report success while
restarting a container that predates a `ports:` change (or one left over from a different
worktree that happens to share this project's container name), which comes up publishing
nothing or the wrong thing. `--force-recreate` guarantees the container you get actually
reflects the compose file you're reading, every time.

---

## Running two stacks at once (parallel worktrees)

Each `git worktree` checkout gets its own Compose project (Compose names it after the checkout
directory), so two checkouts don't share containers — but by default they'd both try to publish
the database on host port `5433`, and only one can hold it. The second `up` would then either
fail to bind or, worse, silently leave you talking to the first checkout's database.

Give each checkout its own port:

```powershell
# worktree A (the default port, nothing to set)
docker compose up -d --force-recreate
.\mvnw.cmd spring-boot:run

# worktree B
$env:DB_HOST_PORT = "5434"
docker compose up -d --force-recreate
.\mvnw.cmd spring-boot:run -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:postgresql://localhost:5434/gastos"
```

To confirm which container is actually serving a port instead of guessing, ask Compose directly
from within the checkout in question:

```powershell
docker compose port db 5432
```

That prints the host address *this project's* `db` service is bound to — if it doesn't match the
port you expected, something else is holding it, not this checkout.

The API starts on **http://localhost:8080**.  
Swagger UI: **http://localhost:8080/swagger-ui.html**

To capture logs:

```powershell
.\mvnw.cmd spring-boot:run > logs\backend.log 2> logs\backend-err.log
```

---

## Testing

```powershell
# All tests (H2 in-memory — no database needed)
.\mvnw.cmd test

# Single test class
.\mvnw.cmd test -Dtest=ExpenseApiIntegrationTest

# Build without tests
.\mvnw.cmd clean install -DskipTests
```

---

## API reference

### Expenses

| Method | Path | Description |
|---|---|---|
| `GET` | `/expenses` | List all expenses |
| `POST` | `/expenses` | Create expense |
| `GET` | `/expenses/{id}` | Get single expense |
| `PUT` | `/expenses/{id}` | Update expense |
| `DELETE` | `/expenses/{id}` | Delete expense |
| `GET` | `/expenses/report/monthly` | Monthly spending totals |
| `GET` | `/expenses/report/category` | Spending totals by category |

### Categories

| Method | Path | Description |
|---|---|---|
| `GET` | `/categories` | List all categories |
| `POST` | `/categories` | Create category |
| `PUT` | `/categories/{id}` | Update category |
| `DELETE` | `/categories/{id}` | Delete category (fails if expenses exist) |

### AI query

| Method | Path | Description |
|---|---|---|
| `POST` | `/ai/query` | Natural-language question → SQL → result |

Request body: `{ "question": "How much did I spend on food?" }`

---

## Project structure

```
src/main/java/com/teng/app/gastosai/
├── ai/           SqlGenerator (OpenAI + Claude), SqlGuard, AiQueryService
├── bootstrap/    Sample data seeder
├── config/       AIClientConfig, WebConfig (CORS)
├── controller/   ExpenseController, CategoryController, AiController
├── dto/          Request/Response records, report items
├── entity/       Expense, Category (JPA entities)
├── exception/    GlobalExceptionHandler
├── repository/   JPA repos with JPQL aggregation queries
└── service/      ExpenseService, CategoryService, AiQueryService
```

---

## Architecture notes

- **Request flow:** `Controller → Service → Repository`
- **AI flow:** `AiController → AiQueryService → SqlGenerator → SqlGuard → JDBC → response`
- **SqlGuard** is the security boundary: blocks all non-SELECT statements, requires `FROM expenses`, rejects multi-statement input, blocks system catalog access.
- **Currency:** Philippine peso (₱), stored as `BigDecimal(19,4)`.
- **DDL:** `validate` + **Flyway** migrations (`db/migration/`) in all environments — schema is versioned and data persists across restarts.

---

## Logs

Runtime logs are written to `logs/` (git-ignored). The directory is created automatically on first run when output is redirected.
