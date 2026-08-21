#!/usr/bin/env bash
# TEN-150 — report payments PayMongo accepted that never became a subscription here.
#
# A subscription is only ever created by a webhook, so a delivery that is lost costs a paying
# customer their access in silence. This asks PayMongo directly about every checkout that is not
# marked paid locally and prints the ones it settled anyway.
#
# The check writes nothing: it reports, a human repairs. So it is safe to run at any time, against
# production, and as often as wanted — two runs in a row report the same rows and change neither.
#
# Usage:
#   scripts/reconcile-payments.sh                      # against the configured database
#   scripts/reconcile-payments.sh --spring.datasource.url=jdbc:postgresql://localhost:5433/gastos_scratch_x
#   scripts/reconcile-payments.sh | grep payment_reconciliation      # just the report
#
# Any argument given is passed through to Spring as-is, which is how a different database or a
# different PayMongo key is selected (--spring.datasource.url=…, --gastos.paymongo.secret-key=…).
#
# Output lines, all prefixed so they can be grepped out of the boot log:
#   payment_reconciliation              the summary: how much was examined, how much was found
#   payment_reconciliation_gap          one discrepancy, with the identifiers to resolve it by hand
#   payment_reconciliation_unresolved   a session the provider could not be asked about — not a
#                                       clean result, and never counted as "not paid"
set -euo pipefail

cd "$(dirname "$0")/.."

# Port 0, not the configured one: this boots the whole application to reach the check — a
# `--spring.main.web-application-type=none` boot does not start, because SecurityConfig needs the
# CORS bean the web context supplies — and an operator is most likely to run this on a machine
# already serving the API. An ephemeral port collides with nothing; the process reports and exits.
arguments="--reconcile-payments --server.port=0"
for extra in "$@"; do
  arguments="$arguments $extra"
done

exec ./mvnw -q spring-boot:run -Dspring-boot.run.arguments="$arguments"
