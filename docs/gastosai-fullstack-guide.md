# gastos.ai — Full-Stack AI Expense Tracker: Complete Build Guide

A step-by-step guide to building a production-ready, AI-powered expense tracker using
**Claude Code**, with a **Spring Boot** backend and a **React + TypeScript** frontend,
deployed on **free-tier** services. Targeted at a backend developer on **Windows** who is
new to frontend and using this as an exploratory project to learn Claude Code and AI agents.

> **Note:** Free tiers and tool versions change over time. Re-verify install commands and
> free-tier limits when you start. This guide assumes Java 25 + Spring Boot 4.

---

## Table of contents

1. [The free stack](#1-the-free-stack)
2. [Install the toolchain (Windows)](#2-install-the-toolchain-windows)
3. [Install Claude Code](#3-install-claude-code)
4. [Project structure](#4-project-structure)
5. [Create the project](#5-create-the-project)
6. [AGENTS.md (project context)](#6-agentsmd-project-context)
7. [The Claude Code workflow](#7-the-claude-code-workflow)
8. [Build the backend](#8-build-the-backend)
9. [Build the frontend](#9-build-the-frontend)
10. [The AI agent feature](#10-the-ai-agent-feature)
11. [Production hardening](#11-production-hardening)
12. [Deployment](#12-deployment)
13. [CI/CD and monitoring](#13-cicd-and-monitoring)
14. [Cost notes & optional hardening](#14-cost-notes--optional-hardening)
15. [Quick command reference](#15-quick-command-reference)

---

## 1. The free stack

| Layer | Service | Free tier notes |
|---|---|---|
| Backend | **Koyeb** | 512 MB RAM, 0.1 vCPU, 2 GB SSD; hosts web services/APIs; supports Spring Boot |
| Frontend | **Vercel** | Free static hosting, auto-deploy on push |
| Database | **Supabase** | Free Postgres (pauses after ~1 week idle) |
| AI / NL queries | **Gemini** | Free request tier (or use OpenAI/Claude — cheap, not free) |
| CI/CD | **GitHub Actions** | Free for personal repos |
| Uptime monitor | **UptimeRobot** | Free tier; keeps backend warm, alerts on downtime |

> Skip **Fly.io** — it no longer offers a free tier for new users. **Render** free web
> services work but spin down after inactivity (~1 min cold start).

---

## 2. Install the toolchain (Windows)

1. **JDK 25** — Temurin 25 MSI from [adoptium.net](https://adoptium.net). Enable
   "Set JAVA_HOME" and "Add to PATH". Verify: `java -version`.
2. **Node.js LTS** — from [nodejs.org](https://nodejs.org). Needed for the frontend and
   Claude Code. Verify: `node --version`.
3. **Git for Windows** — [git-scm.com/downloads/win](https://git-scm.com/downloads/win).
   Also gives you Git Bash, the smoothest shell for Claude Code.
4. **IntelliJ IDEA** — Community Edition is fine for Spring Boot.
5. **Docker Desktop** — for local Postgres.

---

## 3. Install Claude Code

In **PowerShell**:

```powershell
irm https://claude.ai/install.ps1 | iex
claude --version
claude          # authenticate via browser
```

- The native installer needs no dependencies and **auto-updates in the background**.
- **Do not use winget** — it locks `claude.exe` during upgrades and fails with
  "Access is denied." If you have a winget copy, uninstall it first
  (`winget uninstall Anthropic.ClaudeCode`) after closing all Claude Code processes.
- Requires a **Pro, Max, Team, Enterprise, or Console** account (not the free plan).
- With Git for Windows installed, Claude Code uses Git Bash for shell commands.

---

## 4. Project structure

A **monorepo** (backend + frontend in one repo):

```
gastosai/
├── AGENTS.md                      # Claude Code + Copilot both read this
├── README.md
├── docker-compose.yml             # local Postgres
├── .github/workflows/ci.yml       # GitHub Actions: run tests on push
│
├── backend/                       # Spring Boot
│   ├── Dockerfile                 # multi-stage build for deploy
│   ├── pom.xml
│   ├── src/main/java/com/gastos/
│   │   ├── GastosApplication.java
│   │   ├── config/                # SecurityConfig, CorsConfig, AIClientConfig
│   │   ├── expense/               # entity, repo, service, controller, dto
│   │   ├── category/              # Category entity + service
│   │   ├── ai/                    # AiController, AiQueryService, SqlGenerator, SqlGuard
│   │   └── common/                # exceptions, shared utils
│   └── src/main/resources/
│       ├── application.yml
│       ├── application-local.yml
│       ├── application-prod.yml
│       └── db/migration/          # (if/when using Flyway)
│
└── frontend/                      # React + TypeScript (Vite)
    ├── package.json
    ├── vite.config.ts
    ├── .env.local                 # VITE_API_URL=http://localhost:8080
    ├── index.html
    └── src/
        ├── main.tsx
        ├── App.tsx
        ├── api/                   # client.ts + endpoint functions
        ├── pages/                 # Dashboard, Login, Expenses, Ask
        ├── components/            # reusable UI
        ├── hooks/                 # useExpenses, useAuth
        └── lib/                   # formatters, auth token helpers
```

Use **package-by-feature** on the backend (a folder per domain concept, each holding its
own entity/repo/service/controller/DTOs).

---

## 5. Create the project

```powershell
mkdir gastosai; cd gastosai
git init
```

### Backend

Generate at [start.spring.io](https://start.spring.io):

- **Project:** Maven · **Language:** Java · **Spring Boot:** 4.0.x · **Java:** 25 · **Packaging:** Jar
- **Group:** `com.gastos` · **Artifact:** `gastosai`
- **Dependencies:** Spring Web, Spring Data JPA, PostgreSQL Driver, Spring Security,
  Validation, Actuator (add Flyway if you want versioned migrations)

Unzip into `backend/`.

> Spring Boot 4 requires Java 17 minimum and has first-class Java 25 support, so this combo
> is fully supported.

### Frontend (Vite v9 — interactive prompt)

```powershell
npm create vite@latest frontend
```

When prompted: **Framework → React**, **Variant → TypeScript** (the plain one — not the
Compiler, RSC, or Router variants). Then:

```powershell
cd frontend
npm install
npm install axios react-router-dom recharts
npm install -D tailwindcss @tailwindcss/vite
cd ..
```

> TypeScript suits a Java developer — the static typing maps to what you already know.
> Files are `.tsx` / `.ts`.

### Local Postgres

`docker-compose.yml` at the repo root:

```yaml
services:
  db:
    image: postgres:17
    environment:
      POSTGRES_DB: gastos
      POSTGRES_PASSWORD: dev
    ports: ["5432:5432"]
```

Run it:

```powershell
docker compose up -d
```

Point the backend's `local` profile at `localhost:5432`. Commit the scaffold:

```powershell
git add .
git commit -m "Initial scaffold"
```

---

## 6. AGENTS.md (project context)

Create `AGENTS.md` in the repo root. **Both Claude Code and GitHub Copilot read it**, so it
is your single source of truth. Adjust to match what you actually build.

```markdown
# gastosai

AI expense tracker. Spring Boot 4 / Java 25 REST API with a natural-language query
feature backed by a pluggable LLM provider. React + TypeScript frontend.

## Commands (use the Maven wrapper)
- Build (Windows):   mvnw.cmd clean install
- Run (needs .env):  mvnw.cmd spring-boot:run
- All tests:         mvnw.cmd test
- Single test class: mvnw.cmd test -Dtest=ExpenseApiIntegrationTest
- Frontend dev:      cd frontend && npm run dev
- Local DB:          docker compose up -d

## Environment
- Copy .env.example -> .env: DB_URL, DB_USERNAME, DB_PASSWORD, OPENAI_API_KEY or CLAUDE_API_KEY.
- AI provider toggle: GASTOS_AI_PROVIDER=openai | claude.
- Seed sample data on startup: GASTOS_SEED_SAMPLE_DATA=true.
- API docs while running: http://localhost:8080/swagger-ui.html

## Architecture
- Spring Boot 4 / Java 25 REST API. Flow: Controller -> Service -> Repository.
- Persistence: JPA/Hibernate. Postgres in prod, H2 in tests.
- AI flow: AiController -> AiQueryService -> SqlGenerator (OpenAi/Claude) -> SqlGuard
  -> raw JDBC -> formatted response. AIClientConfig selects the provider bean.

## Domain
- Entities: Expense (BigDecimal amount) and Category (unique name).
- CategoryService auto-creates categories when creating expenses; blocks deleting a
  category that still has linked expenses.

## DTOs (API contract separate from entities)
ExpenseRequest/Response, CategoryRequest/Response, AiQueryRequest/Response,
MonthlyReportItem, CategoryReportItem. Never expose entities through controllers.

## AI flow — SAFETY CRITICAL
- ai/SqlGuard.java is the security boundary: no mutating statements, must contain
  FROM expenses, single statement only, blocks system catalogs.
- Never execute AI SQL that bypasses SqlGuard. Both generators must return ONLY a bare SELECT.

## Frontend conventions
- TypeScript only (.tsx/.ts). All HTTP via src/api/client.ts (axios + JWT interceptor).
- Pages in src/pages, reusable UI in src/components, data hooks in src/hooks.
- Tailwind for styling, Recharts for charts. Read VITE_API_URL from env.

## Conventions
- Currency: Philippine peso (₱), BigDecimal, 2 decimals. Timezone: Asia/Manila.
- Secrets come from env vars — never commit them.

## Files to consult first
- ai/SqlGuard.java, ai/SqlGenerator.java + implementations
- controller/*, service/*, repository/*
- .env.example, sample-ai-request.json

## Workflow
- One vertical slice at a time: entity -> repository -> service -> DTO -> controller -> test.
- Run tests before committing. Small focused commits.
- When unsure about a design choice (especially the AI flow), ask before implementing.
```

If you also keep `.github/copilot-instructions.md`, slim it to a one-line pointer to
`AGENTS.md` so you don't maintain two files.

---

## 7. The Claude Code workflow

```powershell
cd gastosai
claude
```

Trust the directory, then work this way:

- **Plan mode** (Shift+Tab): have Claude Code outline a feature *before* writing code so you
  can correct the design first.
- **Vertical slices:** one feature per session, end to end.
- **Review every diff** before accepting — this is where you learn (especially the frontend).
- `/init` generates a draft `AGENTS.md` by scanning the repo.
- As you advance, explore custom slash commands (`.claude/commands/`), subagents
  (`.claude/agents/`), and MCP servers — that progression is a hands-on AI-agents curriculum.

`AGENTS.md` defines *how*, your prompt defines *what*, plan mode lets you check the
*approach*, and the diff review is where you learn.

---

## 8. Build the backend

Prompt one slice per session, in order:

1. **Expenses + Category CRUD** — entities, repositories, services, DTOs (records) with
   validation, controllers, tests.
2. **Reporting** — JPQL aggregation queries (`/expenses/report/monthly`,
   `/expenses/report/category`).
3. **AI query** — see section 10.
4. **(Optional) Auth** — Spring Security + JWT and per-user scoping if you go multi-user.

Example slice prompt:

> Plan and build the expenses CRUD slice per AGENTS.md: entity, repository, service,
> DTOs with validation, controller at /api/expenses, and tests.

---

## 9. Build the frontend

The mental model for a backend dev: **the frontend is just a UI that calls your REST API.**

`src/api/client.ts` — the single place that talks to the backend:

```typescript
import axios from "axios";

const api = axios.create({ baseURL: import.meta.env.VITE_API_URL });

api.interceptors.request.use((cfg) => {
  const token = localStorage.getItem("token");
  if (token) cfg.headers.Authorization = `Bearer ${token}`;
  return cfg;
});

export default api;
```

Then let Claude Code build the UI:

> Build the frontend per AGENTS.md. Pages: Login (stores JWT), Dashboard (spending-by-category
> donut with Recharts + recent expenses), Expenses (table with add/edit/delete), and Ask
> (natural-language box calling POST /api/query, shown conversationally). Use react-router,
> src/api/client.ts for all requests, and Tailwind. Keep components small and readable.

Set `frontend/.env.local`:

```
VITE_API_URL=http://localhost:8080
```

Three concepts to learn while reviewing generated code: **state** (`useState` holds data),
**routing** (`react-router` maps URLs to pages), **components** (reusable UI functions).

---

## 10. The AI agent feature

The `/api/query` endpoint is itself an AI agent and teaches the canonical pattern:

1. User sends natural language.
2. Backend prompts an LLM to produce a query (text-to-SQL).
3. `SqlGuard` validates it (SELECT only, must reference `expenses`, single statement, no
   system catalogs).
4. Run against the database (ideally a **read-only** role for defense in depth).
5. Return the result conversationally.

The lesson: an "AI agent" is *prompt → structured output → tool execution → guardrails* —
the same shape as Claude Code itself.

---

## 11. Production hardening

One Claude Code pass:

> Make the backend production-ready: multi-stage Dockerfile, JVM memory limits for a 512MB
> container, externalized config via env vars in application-prod.yml, CORS for my Vercel
> domain, Actuator health endpoint, global exception handler.

`backend/Dockerfile`:

```dockerfile
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENV JAVA_OPTS="-Xmx320m -XX:+UseSerialGC -XX:MaxRAMPercentage=70"
EXPOSE 8080
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
```

The `JAVA_OPTS` heap cap is **required** for the 512 MB free tier — the JVM's 200–300 MB
overhead will otherwise OOM-kill the app.

Checklist:
- Multi-stage Dockerfile (above)
- JVM memory limits
- Externalized config (`application-prod.yml` reads env vars)
- CORS allowlist for the Vercel domain
- Flyway migrations (if you move off Hibernate `ddl-auto` for prod)
- Actuator `/actuator/health`
- Global `@RestControllerAdvice` for clean JSON errors

---

## 12. Deployment

Push to GitHub first:

```powershell
git add .
git commit -m "Production-ready backend + frontend"
git push
```

### Database
Your Supabase Postgres is already live — grab its connection string for the backend env vars.

### Backend → Koyeb
1. Create a **Web Service** from your GitHub repo; set **build context to `/backend`**
   (uses the Dockerfile).
2. Env vars: `SPRING_PROFILES_ACTIVE=prod`, `DATABASE_URL`, `JWT_SECRET`, and your LLM key
   (`OPENAI_API_KEY` / `CLAUDE_API_KEY` / `GEMINI_API_KEY`), plus `JAVA_OPTS`.
3. Deploy → you get an HTTPS URL.

### Frontend → Vercel
1. Import the same repo; set **root directory to `frontend`**, framework Vite
   (build `npm run build`, output `dist`).
2. Env var `VITE_API_URL` = your Koyeb backend URL.
3. Deploy → HTTPS URL.

### Wire them
Add the Vercel URL to the backend's CORS allowlist (`application-prod.yml`), redeploy the
backend. The live frontend now talks to the live backend.

---

## 13. CI/CD and monitoring

- Koyeb and Vercel **auto-redeploy on every push** — continuous deployment for free.
- Add `.github/workflows/ci.yml` (Claude Code writes it) to run `mvnw.cmd test` on each push
  so broken code never deploys.
- Add a free **UptimeRobot** monitor pinging `/actuator/health` every few minutes to reduce
  cold starts and alert you on downtime.

---

## 14. Cost notes & optional hardening

**Cost:** Everything here is free except the **LLM** powering NL queries — Gemini's free tier
covers light personal use; otherwise it's cents per query. Free-tier tradeoffs (backend
sleeps when idle, Supabase pauses after a week, shared CPU) don't matter for a
learning/portfolio app. A ~$5–7/month upgrade removes them when you need always-on.

**Optional production hardening (when you productionize):**
1. **Read-only DB role for the AI path** — `SqlGuard` is logic, not a hard boundary. Granting
   the AI query path a Postgres role with only `SELECT` means even a guard bug can't mutate
   data. Defense in depth.
2. **Flyway for prod schema** — Hibernate `ddl-auto` on startup is fine for dev but risky in
   prod; switch the prod profile to `validate` + Flyway migrations.
3. **User scoping** — add a `User` entity and scope queries by `user_id` only if/when you go
   multi-user.
4. **Most generous always-on free option** — Oracle Cloud always-free ARM VMs give real RAM
   with no cold starts, at the cost of managing the VM yourself.

---

## 15. Quick command reference

```powershell
# Toolchain checks
java -version
node --version

# Claude Code
claude                 # start a session in the repo root
claude --version
claude doctor          # diagnose install issues

# Local Postgres
docker compose up -d
docker compose down

# Backend (from backend/)
mvnw.cmd clean install
mvnw.cmd spring-boot:run
mvnw.cmd test
mvnw.cmd test -Dtest=ExpenseApiIntegrationTest
mvnw.cmd clean install -DskipTests

# Frontend (from frontend/)
npm run dev
npm run build

# Git
git add .
git commit -m "message"
git push
```

---

### The learning mindset

Treat this as two experiments in parallel: *how to direct an AI agent to build software*
(Claude Code) and *how to build an AI agent feature into software* (`/api/query`). When
something breaks, ask Claude Code to explain its own code before fixing it — every bug
becomes a lesson. Lean on it hardest for the frontend; reading a working app it wrote
against an API you designed is the fastest way to learn React as a backend developer.
