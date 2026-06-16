# Deploy gastosai for user testing (free tier)

A step-by-step guide to put gastosai online for a small group of testers, on free tiers, with **$0 AI cost to you** (each tester brings their own OpenAI key).

**Topology** (Vercel cannot run the Spring Boot backend — it hosts the frontend only):

```
Vercel (frontend, static)  ──HTTPS──►  Fly.io (Spring Boot backend)  ──►  Supabase (PostgreSQL)
                                                  └──►  OpenAI API (gpt-4o-mini)
```

You will need free accounts on **Supabase**, **Fly.io**, and **Vercel**, plus the `flyctl` CLI and your existing GitHub repo.

> Cost summary: **$0 to you.** AI is bring-your-own-key — each tester uses their own OpenAI key; without one, AI features are disabled (no API calls made) and the rest of the app works. You are never billed for AI. See Step 4.

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

# Encrypts each user's stored AI key at rest
flyctl secrets set AI_KEY_ENCRYPTION_SECRET="$(openssl rand -base64 32)"

# AI: bring-your-own-key model — each user adds their OWN OpenAI key in Settings, and
# their AI calls bill to THEIR key. OPENAI_API_KEY below is only a guard placeholder
# (a few code paths require it non-blank) — it is NEVER billed: keyless users are blocked
# (HTTP 402, no call made) and every real call uses the signed-in user's key. You may set
# any non-blank value, or your own key; you will not be charged.
flyctl secrets set OPENAI_API_KEY="unused-placeholder"

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

## Step 4 — Your AI cost is $0 (bring-your-own-key)

This app uses a **bring-your-own-key** model, so **you (the operator) are never billed for AI**:

- AI features are **off until a user adds their own OpenAI key** in Settings. Without a key, the AI endpoints return HTTP 402 **before any API call is made** — zero tokens spent, by you or anyone.
- When a user adds a key, their AI calls bill to **their** key, never yours.
- The `OPENAI_API_KEY` you set on Fly is a guard placeholder only (a few code paths require it non-blank); it is never sent to OpenAI.

So there is nothing to budget-cap on your side. **Only** if you later set `AI_ALLOW_SHARED_KEY=true` (to offer a shared key on your own budget) should you set a real `OPENAI_API_KEY` and a hard monthly cap at https://platform.openai.com → Settings → Billing → Limits.

In-app protections regardless: **10 AI calls/min per user** (`AI_RATE_LIMIT_PER_MINUTE`) + a circuit breaker. Model defaults to the cheap **gpt-4o-mini**.

---

## Step 5 — Smoke test

On the Vercel URL:

1. Register a new account.
2. Add an expense — works with no AI key.
3. Open the Dashboard → the AI Insights card shows **"Connect your OpenAI key in Settings"** (no AI key yet). Chat is disabled with the same prompt. All non-AI features work.
4. Go to **Settings → AI Provider Key**, paste your OpenAI key, Save → status flips to "Using your own OpenAI key"; the insights card + chat enable immediately and bill to **your** key.

If non-AI works but the AI prompt never lifts after adding a key, check: `CORS_ALLOWED_ORIGINS` matches the exact Vercel origin, `AI_KEY_ENCRYPTION_SECRET` is set, and `OPENAI_API_KEY` is non-blank on Fly.

---

## Notes for testers

- Production runs with `GASTOS_SEED_SAMPLE_DATA=false`, so each tester starts with an empty account (recommended — they explore with their own data). If you'd rather seed a shared demo account once, set `GASTOS_SEED_SAMPLE_DATA=true`, deploy, let it boot once, then set it back to `false` and redeploy.
- First request after idle may be slow: Fly scales the machine to zero when unused (cold start ~a few seconds) and Supabase wakes from pause (~30s). Subsequent requests are fast.

---

## Bring-your-own-key (how testers enable AI)

Each tester opens **Settings → AI Provider Key**, pastes their own OpenAI key (get one at platform.openai.com → API keys), and Saves. Their key is stored encrypted (AES-256-GCM, never shown back) and used only for their own AI requests. Until they add a key, AI surfaces show a "Connect your key" prompt and everything else works. They can remove the key anytime. Result: **you bear no AI cost** — each engaged tester funds their own usage.

See `ai/skills/deployment.md` for the alternative Koyeb path and deeper production notes.
