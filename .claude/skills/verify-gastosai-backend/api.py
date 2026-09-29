#!/usr/bin/env python3
"""Drive the locally running gastosai API and print evidence.

    python3 .claude/skills/verify-gastosai-backend/api.py doctor
    python3 .claude/skills/verify-gastosai-backend/api.py login
    python3 .claude/skills/verify-gastosai-backend/api.py req GET /api/v2/expenses --expect 200
    python3 .claude/skills/verify-gastosai-backend/api.py req POST /api/v2/expenses \
        --json '{"amount":15075,"description":"Coffee VERIFY-1","category":"Food"}' --expect 201
    python3 .claude/skills/verify-gastosai-backend/api.py sweep --run-id VERIFY-1 --month 2026-09

Why this exists rather than curl: the round trip a verification needs is login-then-call, and the
token must not land in a transcript, a shell history or an evidence file. `login` caches it in a
0600 file under the run directory and every later `req` reads it from there; nothing prints it.

Three facts this encodes, each of which reads as a broken API when you meet it cold:

- **v1 is unprefixed and v2 is `/api/v2`.** `POST /auth/login` and `POST /api/v2/auth/login` are
  both real; `/api/v1/...` is not a route and answers 404. Pass the path you mean, in full.
- **v2 money is integer centavos, v1 money is a decimal.** `{"amount": 15075}` on
  `/api/v2/expenses` is ₱150.75; the same body on `/expenses` is ₱15,075.00 and validates fine.
  A proof about an amount states which surface it drove.
- **Swagger UI cannot drive an authenticated call.** The published spec declares no security
  scheme, so there is no Authorize control and every protected path answers 401 from the browser
  (TEN-202). That is the tooling, not the API. Use this helper.

Requests go to loopback only, checked after DNS resolution, and redirects are reported rather than
followed — the same restriction `scripts/http_check.py` carries in the workspace.
"""

from __future__ import annotations

import argparse
import ipaddress
import json as jsonlib
import os
import pathlib
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = os.environ.get("VERIFY_API", "http://localhost:8080")
DEMO_EMAIL = os.environ.get("VERIFY_EMAIL", "demo@gastosai.dev")
DEMO_PASSWORD = os.environ.get("VERIFY_PASSWORD", "demo123")
RUN_DIR = pathlib.Path(os.environ.get("VERIFY_RUN_DIR", "/tmp/gastosai-verify"))
REPO = pathlib.Path(__file__).resolve().parents[3]


def token_path(email: str) -> pathlib.Path:
    return RUN_DIR / f"token-{email.replace('@', '_at_')}"


def loopback_only(url: str) -> str:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in ("http", "https"):
        sys.exit(f"refusing {parsed.scheme or '(no scheme)'}:// — only http(s) to localhost")
    host = parsed.hostname or ""
    try:
        infos = socket.getaddrinfo(host, parsed.port or 80, proto=socket.IPPROTO_TCP)
    except socket.gaierror as e:
        sys.exit(f"cannot resolve {host}: {e}")
    for info in infos:
        if not ipaddress.ip_address(info[4][0]).is_loopback:
            sys.exit(f"refusing {host} — it resolves to {info[4][0]}, which is not loopback.")
    return url


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D102
        return None


def call(method: str, path: str, body: str | None = None, token: str | None = None,
         timeout: int = 30) -> tuple[int, str]:
    """One request. Returns (status, raw body); never raises on an HTTP error status."""
    url = loopback_only(API + path)
    req = urllib.request.Request(url, data=body.encode() if body else None, method=method)
    if body:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    opener = urllib.request.build_opener(NoRedirect)
    try:
        with opener.open(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except urllib.error.URLError as e:
        sys.exit(f"could not reach {url}: {e.reason}. Is the API running on {API}?")


def pretty(raw: str, limit: int) -> str:
    try:
        return jsonlib.dumps(jsonlib.loads(raw), indent=2)[:limit]
    except ValueError:
        return raw[:limit]


def login(email: str, password: str, path: str = "/api/v2/auth/login") -> str:
    """Sign in and cache the token 0600. Returns the token; callers must not print it."""
    status, raw = call("POST", path, jsonlib.dumps({"email": email, "password": password}))
    if status == 429:
        sys.exit("HTTP 429 on login — the public rate limit is 10/min per IP. Wait a minute; "
                 "this is not a credentials failure.")
    if status != 200:
        sys.exit(f"HTTP {status} on {path} — {pretty(raw, 400)}")
    token = jsonlib.loads(raw).get("token")
    if not token:
        sys.exit(f"{path} answered 200 with no token: {pretty(raw, 400)}")
    RUN_DIR.mkdir(parents=True, exist_ok=True)
    p = token_path(email)
    p.write_text(token)
    p.chmod(0o600)
    return token


def cached_token(email: str) -> str:
    p = token_path(email)
    if not p.exists():
        return login(email, DEMO_PASSWORD if email == DEMO_EMAIL else "")
    return p.read_text().strip()


def cmd_doctor(args: argparse.Namespace) -> int:
    """Is this instance worth driving? Read-only: starts nothing, writes nothing but the token."""
    rows: list[tuple[str, bool, str]] = []

    db = subprocess.run(["docker", "compose", "ps", "--format", "{{.Service}} {{.State}}"],
                        cwd=str(REPO), capture_output=True, text=True).stdout
    rows.append(("Postgres :5433", "running" in db, "docker compose up -d"))

    health, raw = call("GET", "/actuator/health")
    rows.append((f"Health {health}", health == 200, "./mvnw spring-boot:run"))

    info, info_raw = call("GET", "/actuator/info")
    build = ""
    if info == 200:
        try:
            data = jsonlib.loads(info_raw)
            build = str(data.get("build", {}).get("version") or data.get("app", {}).get("version") or "")
        except ValueError:
            build = ""
    rows.append((f"Build {build or '(no version published)'}", info == 200, "check actuator exposure"))

    docs, _ = call("GET", "/v3/api-docs")
    rows.append(("Contract /v3/api-docs", docs == 200, "springdoc not serving"))

    v1, _ = call("POST", "/auth/login", jsonlib.dumps({"email": args.email, "password": args.password}))
    v2, _ = call("POST", "/api/v2/auth/login", jsonlib.dumps({"email": args.email, "password": args.password}))
    rows.append(("v1 login /auth/login", v1 == 200, f"HTTP {v1}"))
    rows.append(("v2 login /api/v2/auth/login", v2 == 200, f"HTTP {v2}"))

    seeded = "?"
    scoped_ok = False
    if v2 == 200:
        token = login(args.email, args.password)
        code, raw = call("GET", "/api/v2/expenses", token=token)
        if code == 200:
            try:
                seeded = str(len(jsonlib.loads(raw)))
                scoped_ok = True
            except ValueError:
                pass
        anon, _ = call("GET", "/api/v2/expenses")
        rows.append(("Anon GET /api/v2/expenses is 401", anon == 401, f"HTTP {anon}"))
    rows.append((f"Seeded rows for {args.email}: {seeded}", scoped_ok, "GASTOS_SEED_SAMPLE_DATA"))

    width = max(len(r[0]) for r in rows)
    print()
    for name, ok, hint in rows:
        print(f"  {'PASS' if ok else 'FAIL'}  {name:<{width}}  {'' if ok else hint}")
    print(f"\n  API {API}   token cache {RUN_DIR}/ (0600, never printed)")
    return 0 if all(r[1] for r in rows) else 1


def cmd_login(args: argparse.Namespace) -> int:
    token = login(args.email, args.password, args.path)
    print(f"cached a token for {args.email} at {token_path(args.email)} "
          f"({len(token)} chars, not printed)")
    return 0


def cmd_req(args: argparse.Namespace) -> int:
    token = None if args.anon else cached_token(args.email)
    status, raw = call(args.method.upper(), args.path, args.json, token)
    line = f"HTTP {status}  {args.method.upper()} {args.path}"
    out = f"{line}\n{pretty(raw, args.max_body)}"
    print(out)
    if args.save:
        p = pathlib.Path(args.save)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(f"{line}\n{pretty(raw, 1_000_000)}\n")
        print(f"\nsaved {p}")
    if args.expect:
        return 0 if status == args.expect else 1
    return 0 if 200 <= status < 300 else 1


def cmd_runid(args: argparse.Namespace) -> int:
    print(f"VERIFY-{int(time.time())}")
    return 0


def cmd_sweep(args: argparse.Namespace) -> int:
    """Delete only what carries this run's id. Never a bare name prefix, never deleteAll."""
    token = cached_token(args.email)
    removed = {"goals": 0, "expenses": 0, "budgets": 0, "categories": 0}

    def listing(path: str) -> list:
        code, raw = call("GET", path, token=token)
        if code != 200:
            print(f"  ! GET {path} answered {code}; skipping that collection")
            return []
        try:
            data = jsonlib.loads(raw)
        except ValueError:
            return []
        return data if isinstance(data, list) else []

    for goal in listing("/api/v2/goals"):
        if args.run_id in (goal.get("name") or ""):
            call("DELETE", f"/api/v2/goals/{goal['id']}", token=token)
            removed["goals"] += 1

    for exp in listing("/api/v2/expenses"):
        if args.run_id in (exp.get("description") or ""):
            call("DELETE", f"/api/v2/expenses/{exp['id']}", token=token)
            removed["expenses"] += 1

    if args.month:
        for b in listing(f"/api/v2/budgets?month={args.month}"):
            if args.run_id in (b.get("categoryName") or ""):
                call("DELETE", f"/api/v2/budgets/{b['id']}", token=token)
                removed["budgets"] += 1

    for cat in listing("/api/v2/categories"):
        if args.run_id in (cat.get("name") or ""):
            call("DELETE", f"/api/v2/categories/{cat['id']}", token=token)
            removed["categories"] += 1

    print(f"swept {args.run_id}: " + ", ".join(f"{v} {k}" for k, v in removed.items()))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--email", default=DEMO_EMAIL)
    ap.add_argument("--password", default=DEMO_PASSWORD)
    sub = ap.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("doctor", help="read-only: is this instance worth driving?")
    d.set_defaults(fn=cmd_doctor)

    lg = sub.add_parser("login", help="cache a token for later req calls")
    lg.add_argument("--path", default="/api/v2/auth/login")
    lg.set_defaults(fn=cmd_login)

    r = sub.add_parser("req", help="one authenticated request, printed as evidence")
    r.add_argument("method")
    r.add_argument("path", help="full path, e.g. /api/v2/expenses — v1 is unprefixed")
    r.add_argument("--json", help="request body")
    r.add_argument("--anon", action="store_true", help="send no Authorization header")
    r.add_argument("--expect", type=int, help="exit 0 only on this status")
    r.add_argument("--max-body", type=int, default=4000)
    r.add_argument("--save", help="write status line and full body to this file")
    r.set_defaults(fn=cmd_req)

    ri = sub.add_parser("runid", help="print a fresh run id to stamp rows with")
    ri.set_defaults(fn=cmd_runid)

    s = sub.add_parser("sweep", help="delete rows carrying a run id")
    s.add_argument("--run-id", required=True)
    s.add_argument("--month", help="also sweep budgets in this YYYY-MM")
    s.set_defaults(fn=cmd_sweep)

    args = ap.parse_args()
    return args.fn(args)


if __name__ == "__main__":
    raise SystemExit(main())
