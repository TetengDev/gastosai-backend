#!/usr/bin/env bash
#
# Takes a full logical backup BEFORE Flyway applies anything, and FAILS CLOSED: if the dump
# cannot be produced or looks truncated, this exits non-zero so the deploy stops. A migration
# without a restorable backup is the one thing rollback cannot undo — an expand-contract step
# that drops a column takes the data with it.
#
# Usage:
#   scripts/backup-before-migrate.sh [output-dir]
#
# Reads DB_URL / DB_USERNAME / DB_PASSWORD (the same vars the app uses). DB_URL is the JDBC
# form, e.g. jdbc:postgresql://host:5432/gastos — parsed here into pg_dump arguments.
#
# Restore:
#   gunzip -c <dump>.sql.gz | psql "postgresql://user@host:port/db"

set -euo pipefail

OUT_DIR="${1:-backups}"

: "${DB_URL:?DB_URL is required (jdbc:postgresql://host:port/database)}"
: "${DB_USERNAME:?DB_USERNAME is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"

if ! command -v pg_dump >/dev/null 2>&1; then
  echo "ERROR: pg_dump not found. Install the postgresql client tools." >&2
  exit 1
fi

# jdbc:postgresql://host:port/database?params -> host, port, database
uri="${DB_URL#jdbc:postgresql://}"
uri="${uri%%\?*}"
hostport="${uri%%/*}"
database="${uri#*/}"
host="${hostport%%:*}"
port="${hostport##*:}"
[ "$port" = "$host" ] && port=5432

if [ -z "$host" ] || [ -z "$database" ]; then
  echo "ERROR: could not parse DB_URL ('$DB_URL'). Expected jdbc:postgresql://host:port/database" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="$OUT_DIR/${database}-pre-migrate-${stamp}.sql.gz"

echo "Backing up ${database} at ${host}:${port} -> ${target}"

# Remove the partial file on any failure. Without this a failed dump leaves a truncated
# artifact on disk that a later operator could mistake for a usable backup.
cleanup_partial() {
  local code=$?
  if [ "$code" -ne 0 ] && [ -e "$target" ]; then
    rm -f "$target"
    echo "Removed partial backup ${target}." >&2
  fi
  exit "$code"
}
trap cleanup_partial EXIT

# pipefail is set, so a pg_dump failure fails the pipeline even though gzip succeeds.
PGPASSWORD="$DB_PASSWORD" pg_dump \
  --host="$host" \
  --port="$port" \
  --username="$DB_USERNAME" \
  --dbname="$database" \
  --no-owner \
  --no-privileges \
  --format=plain \
  | gzip > "$target"

# A dump that exists but is tiny means the connection succeeded and produced nothing useful.
size=$(wc -c < "$target" | tr -d ' ')
if [ "$size" -lt 1024 ]; then
  echo "ERROR: backup is only ${size} bytes — refusing to proceed with the migration." >&2
  exit 1
fi

# gzip integrity: catches a dump truncated by a mid-stream disconnect.
if ! gzip -t "$target"; then
  echo "ERROR: backup failed its integrity check — refusing to proceed with the migration." >&2
  exit 1
fi

echo "Backup OK (${size} bytes): ${target}"
