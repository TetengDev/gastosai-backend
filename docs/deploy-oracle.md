# Deploy the backend to Oracle Cloud Always Free (Singapore, ARM)

Move the gastosai backend off Render (which sleeps and cold-starts) onto an
**Oracle Cloud Always Free** Ampere A1 VM: always-on, no sleep, ~12 GB RAM,
`ap-singapore-1` region (~20–40 ms from the Philippines). Frontend stays on
**Vercel**, database stays on **Supabase**.

> One-time cost: Oracle requires a **$1 card authorization hold** (reversed) for
> identity verification at signup. Always Free compute itself never bills.

The app container is unchanged — `backend/Dockerfile` base images are multi-arch,
so `docker build` on the ARM VM yields a native `arm64` image. Caddy fronts it
with automatic HTTPS so the HTTPS Vercel site can call the API.

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

## 4. Get the code + configure secrets

```bash
git clone https://github.com/TetengDev/gastosai.git
cd gastosai/backend
cp .env.prod.example .env.prod
nano .env.prod        # fill DOMAIN, CORS_ALLOWED_ORIGINS, DB_*, secrets, AI keys
```

Key values:
- `DOMAIN` = your DuckDNS hostname (drives the TLS cert).
- `CORS_ALLOWED_ORIGINS` = your Vercel origin (comma-separated for several).
- `DB_*` = Supabase Session-mode string (port 5432).
- `JWT_SECRET`, `AI_KEY_ENCRYPTION_SECRET` = strong random values.

> Schema is managed by Flyway (migrations V1–V7 in `src/main/resources/db/migration`)
> and `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`. Migrations apply automatically on
> first boot against the Supabase DB.

---

## 5. Build + run

```bash
docker compose -f compose.prod.yml --env-file .env.prod up -d --build
```

First boot: the ARM Maven build takes a few minutes; the JVM then cold-boots
(~1–3 min) — the compose healthcheck allows for this via `start_period`.

Watch it come up:
```bash
docker compose -f compose.prod.yml logs -f api
docker compose -f compose.prod.yml ps        # api healthy, caddy running
```

Verify HTTPS + the app:
```bash
curl https://gastosai.duckdns.org/actuator/health     # {"status":"UP"}
```

---

## 6. Repoint the frontend (Vercel)

1. Vercel → project → Settings → Environment Variables: set
   `VITE_API_URL=https://gastosai.duckdns.org`.
2. Redeploy (or push to `master`).
3. Confirm the app loads, logs in, and the chat/AI calls hit the new host.
4. Decommission the Render service once the new host is verified.

---

## 7. Operations

- **Update to a new release:** `git pull && docker compose -f compose.prod.yml --env-file .env.prod up -d --build`
- **Logs:** `docker compose -f compose.prod.yml logs -f api` (prod profile emits ECS JSON — see `docs/observability.md`).
- **Restart on reboot:** `restart: unless-stopped` handles it; Docker starts on boot by default on Ubuntu.
- **TLS renewal:** automatic via Caddy.

---

## Notes / gotchas

- **ARM image:** no change needed — building on the VM is native `arm64`. (If you ever build *for* the VM from an x86 machine, use `docker buildx build --platform linux/arm64`.)
- **Supabase** free projects pause after ~1 week idle; first request after a pause takes ~30 s.
- **Memory:** the VM has far more RAM than Render's 512 MB; you can relax `JAVA_OPTS` in `backend/Dockerfile` (e.g. `-Xmx768m`) if you want more headroom, but the current tuning is safe.
- **No-domain alternative to Caddy+DuckDNS:** a free **Cloudflare Tunnel** (`cloudflared`) also gives HTTPS without opening 80/443 or owning a domain — swap the `caddy` service for a `cloudflared` container pointed at `api:8080`.
