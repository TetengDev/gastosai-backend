# Deploy the backend to Oracle Cloud Always Free (Singapore, ARM)

Move the gastosai backend off Render (which sleeps and cold-starts) onto an
**Oracle Cloud Always Free** Ampere A1 VM: always-on, no sleep, ~12 GB RAM,
`ap-singapore-1` region (~20–40 ms from the Philippines). Frontend stays on
**Vercel**, database stays on **Supabase**.

> One-time cost: Oracle requires a **$1 card authorization hold** (reversed) for
> identity verification at signup. Always Free compute itself never bills.

**The VM does not build anything.** `.github/workflows/build-image.yml` publishes a
multi-arch (`amd64` + `arm64`) image to GHCR on every push to `main`, tagged with the
application version and the commit SHA — never `:latest`. The VM pulls one pinned tag,
named by `IMAGE_TAG` in `.env.prod`. That is what makes a rollback a tag change and a
`docker compose up -d` rather than a rebuild, and what makes "what is running in prod?"
a question with an exact answer.

Caddy fronts the JVM with automatic HTTPS so the HTTPS Vercel site can call the API.

---

## 0. Prerequisites

- A domain or free subdomain that can point an `A` record at the VM IP. Easiest
  free option: **DuckDNS** (`<name>.duckdns.org`). Caddy needs a real hostname to
  get a Let's Encrypt cert (a bare IP can't get public TLS).
- Your Supabase connection string and the app secrets (see `.env.prod.example`).
- Your Vercel frontend origin (e.g. `https://gastosai.vercel.app`).

---

## 1. Create the Oracle account + VM

1. Sign up at <https://www.oracle.com/cloud/free/>. **Choose `Singapore` (ap-singapore-1) as your home region — it cannot be changed later.** Complete the $1 card-hold verification.
2. Compute → Instances → **Create instance**:
   - **Image:** Canonical Ubuntu 22.04 (or 24.04).
   - **Shape:** change to **Ampere (Arm)** → `VM.Standard.A1.Flex`. Set **1–2 OCPU + 6–12 GB RAM** (well within Always Free).
   - **Networking:** create/keep a VCN with a public subnet; **assign a public IPv4**.
   - **SSH keys:** upload your public key (or let it generate one; save the private key).
3. If you hit **"Out of host capacity"** in Singapore (common for ARM): retry over a few hours, or script it with the OCI CLI in a loop. It eventually provisions.
4. Note the VM's **public IP**.

### Open the ports
- **OCI security list / NSG:** add ingress rules for TCP **80** and **443** from `0.0.0.0/0` (Caddy/ACME + HTTPS). (Port 8080 stays internal — do *not* expose it.)
- **On the VM**, Ubuntu's host firewall also blocks by default:
  ```bash
  sudo iptables -I INPUT 5 -p tcp --dport 80 -j ACCEPT
  sudo iptables -I INPUT 5 -p tcp --dport 443 -j ACCEPT
  sudo netfilter-persistent save
  ```

---

## 2. Point DNS at the VM

DuckDNS: create a subdomain, set its IP to the VM's public IP. Verify:
```bash
dig +short gastosai.duckdns.org   # should print the VM IP
```

---

## 3. Install Docker on the VM

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl git
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=arm64 signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo usermod -aG docker $USER && newgrp docker
```

---

## 4. Authenticate to GHCR

The image lives in GitHub Packages and the **container package is private** — repository
visibility and package visibility are separate settings, so the repos being public does not
make the image public. The VM has to log in once:

```bash
echo "$GHCR_TOKEN" | docker login ghcr.io -u <your-github-username> --password-stdin
```

`GHCR_TOKEN` is a **classic** PAT with `read:packages` (the same shape as `PACKAGE_TOKEN` —
the existing one works). Credentials persist in `~/.docker/config.json`, so this is one-time.

> Alternatively, make the *container* package public and skip this step entirely. Unlike the
> npm contract package there is no real argument against it: the image is a build artifact,
> not a consumable API. See `docs/PACKAGE-AUTH.md` for why the contract package stays private.

---

## 5. Get the deploy files + configure secrets

Only `compose.prod.yml`, `Caddyfile` and `.env.prod` are needed on the VM — no source, no
Maven, no JDK.

```bash
git clone https://github.com/TetengDev/gastosai-backend.git
cd gastosai-backend
cp .env.prod.example .env.prod
nano .env.prod
```

Fill in:

| Variable | Value |
|---|---|
| `IMAGE_TAG` | the version to run, e.g. `0.64.0`. **Required** — compose refuses to start without it |
| `DOMAIN` | your DuckDNS hostname (drives the TLS cert) |
| `CORS_ALLOWED_ORIGINS` | your Vercel origin (comma-separated for several) |
| `DB_*` | Supabase Session-mode string (port 5432) |
| `JWT_SECRET`, `AI_KEY_ENCRYPTION_SECRET` | strong random values |
| `OPENAI_API_KEY`, `PAYMONGO_*`, `MAIL_*`, `FRONTEND_BASE_URL` | as before |

> In the prod profile startup **fails fast** if `JWT_SECRET` or `AI_KEY_ENCRYPTION_SECRET`
> still equals its dev default. That is deliberate — a placeholder secret stops the deploy
> rather than silently weakening auth.

Available image tags:

```bash
# every published version
gh api /orgs/TetengDev/packages/container/gastosai-backend/versions \
  --jq '.[].metadata.container.tags[]' | head
```

---

## 6. Back up before deploying

Schema is owned by Flyway (22 migrations in `src/main/resources/db/migration`) with
`SPRING_JPA_HIBERNATE_DDL_AUTO=validate`. **Any unapplied migration runs automatically on
boot, against live data.** Take a restorable dump first:

```bash
DB_URL="jdbc:postgresql://<supabase-host>:5432/postgres" \
DB_USERNAME=postgres DB_PASSWORD='<pw>' \
scripts/backup-before-migrate.sh backups
```

The script **fails closed** — it verifies the dump is non-trivial, checks gzip integrity, and
deletes any partial file. If it exits non-zero, do not deploy. A migration that drops a column
is the one thing an image rollback cannot undo.

To see what will actually apply, compare the applied versions against the migration files:

```sql
select version, description, success from flyway_schema_history order by installed_rank desc limit 5;
```

Re-deploying the *same* application version applies nothing.

---

## 7. Deploy

```bash
docker compose -f compose.prod.yml --env-file .env.prod pull
docker compose -f compose.prod.yml --env-file .env.prod up -d
docker compose -f compose.prod.yml --env-file .env.prod logs -f api
```

First boot cold-starts the JVM (~1–3 min); the compose healthcheck allows for it via
`start_period: 180s`.

Verify:

```bash
curl https://$DOMAIN/actuator/health                          # {"status":"UP"}
curl -s https://$DOMAIN/v3/api-docs | jq '.paths | length'     # 61
docker compose -f compose.prod.yml --env-file .env.prod ps     # api healthy, caddy running
```

**The path count matters.** It must match the published contract that clients are pinned to
(`@tetengdev/gastosai-api-contract`). If it differs, the deployed backend and the contract have
diverged and every generated client is working from a spec the server no longer honours.

Timestamps must carry the `+08:00` offset. A naive timestamp means an older image is running —
clients would then resolve times against their own timezone rather than Manila:

```bash
# Log in and read one dated payload back.
TOKEN=$(curl -s -X POST https://$DOMAIN/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"<user>","password":"<pw>"}' | jq -r .token)

curl -s -H "Authorization: Bearer $TOKEN" "https://$DOMAIN/expenses" \
  | jq -r '.[0].date'
# expect e.g. 2026-06-26T12:00:00+08:00   — an offset-less value is a stale image
```

---

## 8. Point the frontend at it (Vercel)

1. Vercel project → **Settings → Git**: connect `TetengDev/gastosai-web`, production branch
   **`main`** (the old repo used `master`).
2. **Settings → Environment Variables:**

   | Variable | Value |
   |---|---|
   | `VITE_API_URL` | `https://$DOMAIN` |
   | `PACKAGE_TOKEN` | classic PAT with `read:packages` — **required**; `npm ci` cannot resolve the pinned contract package without it, so the build fails at install |
   | `VITE_BILLING_ENABLED` | `false` to hide payment UI (free-launch), or omit |

3. Redeploy, then confirm the app loads, logs in, and AI calls reach the new host.

`vercel.json` already contains the SPA rewrite — no extra routing config needed.

---

## 9. Operations

**Deploy a new version** — no rebuild on the VM:
```bash
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=0.65.0/' .env.prod
docker compose -f compose.prod.yml --env-file .env.prod pull
docker compose -f compose.prod.yml --env-file .env.prod up -d
```

**Roll back** — the same operation pointed backwards:
```bash
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=<previous-version>/' .env.prod
docker compose -f compose.prod.yml --env-file .env.prod up -d
```

Rollback only reaches versions `build-image.yml` has actually published. A schema change is
*not* covered — expand-contract migrations exist precisely so an older image can still run
against the newer schema.

- **Logs:** `docker compose -f compose.prod.yml --env-file .env.prod logs -f api` (prod profile
  emits ECS JSON — see `docs/observability.md`).
- **Restart on reboot:** `restart: unless-stopped`; Docker starts on boot by default on Ubuntu.
- **TLS renewal:** automatic via Caddy.

---

## Notes / gotchas

- **ARM:** the published image is multi-arch, so the Ampere A1 pulls `arm64` automatically.
- **Supabase** free projects pause after ~1 week idle; the first request after a pause takes ~30 s.
- **Memory:** `JAVA_OPTS` in the Dockerfile is tuned for 512 MB (`-Xmx320m`). The VM has far
  more; raising it means rebuilding the image, not editing the VM.
- **No-domain alternative to Caddy+DuckDNS:** a free **Cloudflare Tunnel** (`cloudflared`) also
  gives HTTPS without opening 80/443 or owning a domain — swap the `caddy` service for a
  `cloudflared` container pointed at `api:8080`.
- **Redis** is optional and off by default; only needed to share rate-limit counters across
  more than one API instance (`--profile redis` + `RATELIMIT_REDIS_ENABLED=true`).
