# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./mvnw clean install

# Run (requires .env file — copy from .env.example)
./mvnw spring-boot:run

# Run tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=ExpenseApiIntegrationTest

# Skip tests during build
./mvnw clean install -DskipTests
```

## Environment Setup

Copy `.env.example` to `.env` at the project root and fill in values. The app loads `.env` from the IDE working directory or the `gastosai/` subdirectory automatically at startup.

Required env vars: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `OPENAI_API_KEY` or `CLAUDE_API_KEY`.

Set `GASTOS_AI_PROVIDER=openai` or `GASTOS_AI_PROVIDER=claude` to select the AI backend.

Set `GASTOS_SEED_SAMPLE_DATA=true` to load sample expenses on first startup.

API docs available at `http://localhost:8080/swagger-ui.html` when running.

## Architecture

Spring Boot 4.0.5 / Java 25 REST API. PostgreSQL via JPA/Hibernate with DDL auto-creation on startup. Tests use H2 in-memory.

**Request flow:** `Controller → Service → Repository`. The AI query flow deviates: `AiController → AiQueryService → SqlGenerator (Claude or OpenAI) → SqlGuard validation → raw JDBC execution → formatted response`.

**AI integration** (`ai/` package): `SqlGenerator` is an interface with two implementations — `ClaudeSqlGenerator` and `OpenAiSqlGenerator`. The active bean is selected by `GASTOS_AI_PROVIDER` via `AIClientConfig`. Both call their respective REST APIs using Spring `RestClient` with per-provider base URLs and auth headers configured in `AIClientConfig`. Prompts instruct the model to return only a bare SQL SELECT statement.

**SqlGuard** (`ai/SqlGuard.java`) validates every AI-generated SQL before execution: blocks mutating statements (INSERT/UPDATE/DELETE/DROP/etc.), requires a `FROM expenses` clause, rejects multiple statements, and blocks system catalog access. Any violation throws before the query runs.

**AI provider config** is split into `ClaudeProperties`, `OpenAiProperties` (model + API key), and `AiProviderProperties` (which provider to activate), all bound via `@ConfigurationProperties`.

**Domain model**: three entities — `Expense` (amount `BigDecimal(19,4)`, date `LocalDateTime`, description `String`, FK to Category), `Category` (unique name `String(50)`, icon `String(50)` nullable), `User` (email unique, name, nickname, avatarColor `String(20)`, password BCrypt, createdAt). JWT is issued on login, register, and profile update; subject = email. `CategoryService` auto-creates a category by name when creating an expense if it doesn't exist, and reassigns expenses to Uncategorized on category delete.

**Reporting**: `ExpenseRepository` has JPQL queries for monthly aggregation (year/month + total) and category aggregation (category name + total), surfaced via `GET /expenses/report/monthly` and `GET /expenses/report/category`.

**Exception handling**: `GlobalExceptionHandler` (`@ControllerAdvice`) catches `ResourceNotFoundException` and validation errors and maps them to structured error responses.
