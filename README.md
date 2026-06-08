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
| `DB_URL` | Yes | JDBC URL — e.g. `jdbc:postgresql://localhost:5433/gastos` |
| `DB_USERNAME` | Yes | Database user — default `postgres` |
| `DB_PASSWORD` | Yes | Database password |
| `OPENAI_API_KEY` | One of these | OpenAI API key |
| `CLAUDE_API_KEY` | One of these | Anthropic API key |
| `GASTOS_AI_PROVIDER` | No | `openai` (default) or `claude` |
| `GASTOS_SEED_SAMPLE_DATA` | No | `true` seeds 15 sample expenses on first run |

> The database must be running before starting the backend.
> Start it with `docker compose up -d` from the repo root.

---

## Running

```powershell
.\mvnw.cmd spring-boot:run
```

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
.\mvnw.cmd test -Dtest=ExpenseApiIT

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
- **DDL:** `create-drop` in dev (schema rebuilt on each start). Switch to `validate` + Flyway for production.

---

## Logs

Runtime logs are written to `logs/` (git-ignored). The directory is created automatically on first run when output is redirected.
