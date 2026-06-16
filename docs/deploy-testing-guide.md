# Deploy gastosai for user testing (free tier)

A step-by-step guide to put gastosai online for a small group of testers, on free tiers, with your OpenAI cost capped to near-zero.

**Topology** (Vercel cannot run the Spring Boot backend — it hosts the frontend only):

```
Vercel (frontend, static)  ──HTTPS──►  Fly.io (Spring Boot backend)  ──►  Supabase (PostgreSQL)
                                                  └──►  OpenAI API (gpt-4o-mini)
```

You will need free accounts on **Supabase**, **Fly.io**, and **Vercel**, plus the `flyctl` CLI and your existing GitHub repo.

> Cost summary: the only paid dependency is OpenAI. The app defaults to **gpt-4o-mini** (very cheap) and limits each user to 10 AI calls/min. With a **hard monthly budget cap** set in the OpenAI dashboard (Step 4), your worst case is bounded to that cap. For a handful of testers, expect cents.

---

## Step 1 — Database (Supabase)

1. Create a project at https://supabase.com (free tier). Pick a region near your testers.
2. Project Settings → **Database** → **Connection string** → **Session** mode (port **5432**). Note:
   - host (e.g. `db.abcdxyz.supabase.co`)
   - user (`postgres`)
   - password (the DB password you set when creating the project)
3. Nothing else to do — the app's Flyway migrations (V1–V4) create the schema automatically on first backend boot. The DB starts empty.

> Supabase pauses a free project after ~1 week of inactivity; the next request wakes it (~30s). Fine for testing.

---

## Step 2 — Backend (Fly.io)

Install the CLI: https://fly.io/docs/flyctl/install/ , then `flyctl auth login`.

From the **`backend/`** directory (it contains `Dockerfile` + `fly.toml`):

```powershell
cd backend
flyctl launch --no-deploy        # detects fly.toml + Dockerfile; pick an app name + region
```

Set secrets (these are NOT committed; they override the placeholders in `fly.toml`):

```powershell
# Database (from Step 1)
flyctl secrets set DB_URL="jdbc:postgresql://<supabase-host>:5432/postgres"
flyctl secrets set DB_USERNAME="postgres"
flyctl secrets set DB_PASSWORD="<supabase-password>"

# Security — generate a long random secret (>=32 chars)
flyctl secrets set JWT_SECRET="$(openssl rand -base64 48)"   # or any 48+ random chars

# AI (your key for now; BYO-key per tester ships in a later release)
flyctl secrets set OPENAI_API_KEY="sk-...your-key..."

# CORS — set AFTER Step 3 once you have the Vercel URL, then redeploy
# flyctl secrets set CORS_ALLOWED_ORIGINS="https://<your-app>.vercel.app"
```

Non-secret config (`SPRING_PROFILES_ACTIVE=prod`, `GASTOS_SEED_SAMPLE_DATA=false`, `OPENAI_MODEL=gpt-4o-mini`, `AI_RATE_LIMIT_PER_MINUTE=10`, `MONETIZATION_ENFORCE=false`, etc.) is already in `fly.toml`.

Deploy:

```powershell
flyctl deploy
```

Grab the backend URL: `https://<your-app>.fly.dev`. Check health: open `https://<your-app>.fly.dev/actuator/health` → should return `{"status":"UP"}`.

---

## Step 3 — Frontend (Vercel)

1. https://vercel.com → **Add New… → Project** → import your GitHub repo.
2. **Root Directory**: `frontend`
3. Framework preset: **Vite**. Build command `npm run build`, output directory `dist` (defaults are correct).
4. **Environment Variables** → add `VITE_API_URL = https://<your-app>.fly.dev` (your Fly URL from Step 2).
5. Deploy. Note the Vercel URL, e.g. `https://gastosai.vercel.app`.
   - `frontend/vercel.json` is already committed so client-side routing (React Router) works.

Now wire CORS back to the backend:

```powershell
cd backend
flyctl secrets set CORS_ALLOWED_ORIGINS="https://<your-app>.vercel.app"
flyctl deploy
```

(If you use a custom domain or Vercel preview URLs, add them comma-separated.)

---

## Step 4 — Cap your OpenAI cost (do this — it's the real backstop)

In https://platform.openai.com :

1. **Settings → Billing → Limits**: set a **hard monthly budget** (e.g. **$5**) and a lower **usage alert** (e.g. $2). When the hard cap is hit, OpenAI stops serving requests — your spend cannot exceed it.
2. Keep the model on **gpt-4o-mini** (already the default `OPENAI_MODEL`). It's roughly 100× cheaper than GPT-4-class models.
3. In-app protections already active: **10 AI calls/min per user** (`AI_RATE_LIMIT_PER_MINUTE`) and a circuit breaker that fast-fails if the provider errors repeatedly.

> Want even tighter control? Lower `AI_RATE_LIMIT_PER_MINUTE` (e.g. 5) via `flyctl secrets set` and redeploy.

---

## Step 5 — Smoke test

On the Vercel URL:

1. Register a new account.
2. Add an expense.
3. Open the Dashboard → confirm an AI insight renders (this exercises CORS + auth + OpenAI end-to-end).
4. Watch the OpenAI **Usage** dashboard — you should see a tiny amount of usage and your budget cap in place.

If the AI insight fails but the rest works, check: `CORS_ALLOWED_ORIGINS` matches the exact Vercel origin, and `OPENAI_API_KEY` is set on Fly.

---

## Notes for testers

- Production runs with `GASTOS_SEED_SAMPLE_DATA=false`, so each tester starts with an empty account (recommended — they explore with their own data). If you'd rather seed a shared demo account once, set `GASTOS_SEED_SAMPLE_DATA=true`, deploy, let it boot once, then set it back to `false` and redeploy.
- First request after idle may be slow: Fly scales the machine to zero when unused (cold start ~a few seconds) and Supabase wakes from pause (~30s). Subsequent requests are fast.

---

## Coming next: bring-your-own-key (BYO)

A follow-up release adds a Settings option where each tester pastes their **own** OpenAI key (stored encrypted). When set, that tester's AI calls bill to *their* key; when not set, calls fall back to your shared (capped) key. That removes cost from your side for engaged users while keeping zero setup for casual testers. When it ships, also set `flyctl secrets set AI_KEY_ENCRYPTION_SECRET="$(openssl rand -base64 32)"`.

See `ai/skills/deployment.md` for the alternative Koyeb path and deeper production notes.
