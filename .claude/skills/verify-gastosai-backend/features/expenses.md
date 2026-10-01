# Expenses

An expense is the product's core row: an amount, a category, a date, a description, and an optional
currency with an exchange rate. Clients create, list, page, read, update and delete them, and every
call is scoped to the signed-in user. The amount is the one field whose representation differs by
surface — integer centavos on v2, a decimal on v1 — and that difference is what most expense proofs
are actually about.

## Sub-features

- `create-v2` — `POST /api/v2/expenses` with `{"amount": 15075}` stores ₱150.75 and answers `201`.
- `readback` — `GET /api/v2/expenses/{id}` returns the stored row; the amount comes back as the same
  integer.
- `centavos-invariant` — the same row read on v1 (`GET /expenses/{id}`) reports `150.75`. One row,
  two representations, no rounding drift.
- `list` — `GET /api/v2/expenses` returns this account's rows, optionally bounded by `from` and `to`.
- `page` — `GET /api/v2/expenses/page?page=0&size=20` returns a `PageResponse` envelope for the
  client's infinite list.
- `update` — `PUT /api/v2/expenses/{id}` changes fields; `source` is ignored on update, recorded once
  at creation.
- `delete` — `DELETE /api/v2/expenses/{id}` removes one row and a later read is `404`.
- `validate-amount` — `0`, a negative, or an amount over `Money.MAX_CENTAVOS` is `400` on v2.
- `validate-source` — a `source` outside `MANUAL` / `RECEIPT_SCAN` is `400` naming the field.
- `scoped` — none of these paths answer without a token, and none reach another account's rows.

## How to get to it (user POV)

- Web: the Expenses page's add-expense modal and its row actions → `POST`, `PUT`,
  `DELETE /api/v2/expenses`; the list and its "load more" → `GET /api/v2/expenses/page`.
- Web dashboard: the KPI strip and charts read the same rows through the list and report endpoints.
- Mobile: the add-expense flow and the expense list, same v2 routes through `src/api/client.ts`.
- Chat assistant and quick-add: `POST /api/v2/expenses/parse` and `/quick-add` write rows on the
  user's behalf — a different entry point to the same table, not yet mapped.
- CSV import (`POST /api/v2/expenses/import`) — also writes rows, not yet mapped.

## Driving it with api.py

Preconditions: doctor passes; `S=.claude/skills/verify-gastosai-backend/api.py`;
`D=~/.claude/gastosai-backend-verify/$(date +%F)`; `RID=$(python3 $S runid)`; `python3 $S login`.

- **Create the row a client would create (`create-v2`)**:

  ```bash
  python3 $S req POST /api/v2/expenses --expect 201 --save $D/create.json \
      --json "{\"amount\":15075,\"description\":\"Coffee $RID\",\"category\":\"Food\"}"
  ```

  Observable: `201`, `"amount": 15075`, `"source": "MANUAL"` (omitted means manual),
  `"currency": "PHP"`, `"exchangeRate": 1.0`, and an `id`. Keep that id; the steps below need it.

- **Read it back (`readback`)** — a `201` alone is not a proof:

  ```bash
  python3 $S req GET /api/v2/expenses/<id> --expect 200 --save $D/readback-v2.json
  ```

  Observable: `"amount": 15075` again, and `amountInBaseCurrency` equal to it at rate `1.0`.

- **Cross the surfaces (`centavos-invariant`)** — the same row, unprefixed:

  ```bash
  python3 $S req GET /expenses/<id> --expect 200 --save $D/readback-v1.json
  ```

  Observable: `"amount": 150.75`. Verified this way on 2026-09-24 against build `0.94.3`: `15075`
  in, `15075` out of v2, `150.75` out of v1. v1 also carries `project` / `projectId`, which v2 drops.

- **List and page (`list`, `page`)**:

  ```bash
  python3 $S req GET /api/v2/expenses --expect 200 --max-body 400
  python3 $S req GET "/api/v2/expenses/page?page=0&size=5" --expect 200 --max-body 800
  ```

  Observable: the list contains the new description; the paged call returns an envelope, not a bare
  array. The seeded count drifts (191 on 2026-09-24) — assert the delta your create produced, never
  a total.

- **Update it (`update`)**:

  ```bash
  python3 $S req PUT /api/v2/expenses/<id> --expect 200 \
      --json "{\"amount\":20000,\"description\":\"Coffee $RID\",\"category\":\"Food\"}"
  python3 $S req GET /api/v2/expenses/<id> --expect 200
  ```

  Observable: `"amount": 20000` on the read-back. The mutation is proven by the second call, not by
  the `200` on the first.

- **Reject a bad amount (`validate-amount`)**:

  ```bash
  python3 $S req POST /api/v2/expenses --expect 400 \
      --json "{\"amount\":0,\"description\":\"Zero $RID\",\"category\":\"Food\"}"
  ```

  Observable: `400`. v2 bounds the amount at `@Min(1)`, so zero and negatives never reach the
  service. The same body on `/expenses` is a different claim — v1 bounds a decimal.

- **Reject an undeclarable source (`validate-source`)**:

  ```bash
  python3 $S req POST /api/v2/expenses --expect 400 \
      --json "{\"amount\":100,\"description\":\"Src $RID\",\"category\":\"Food\",\"source\":\"TELEPATHY\"}"
  ```

  Observable: `400` with
  `"detail": "Invalid value for field 'source'. Allowed values: MANUAL, QUICK_ADD, RECEIPT_SCAN, RECURRING, IMPORT."`
  — an unknown name fails inside Jackson and the `HttpMessageNotReadableException` handler answers.

  A *real* enum value that a client may not declare takes the other path, and the message differs:

  ```bash
  python3 $S req POST /api/v2/expenses --expect 400 \
      --json "{\"amount\":100,\"description\":\"Imp $RID\",\"category\":\"Food\",\"source\":\"IMPORT\"}"
  ```

  Observable: `400` with `"detail": "source must be MANUAL or RECEIPT_SCAN — got 'IMPORT'."` — that
  one is `ExpenseService` refusing a value that bound cleanly. Both messages verified on
  2026-09-24 against build `0.94.3`. The enum is `MANUAL`, `QUICK_ADD`, `RECEIPT_SCAN`, `RECURRING`,
  `IMPORT`; there is no `CHAT`, so a chat-written row is not distinguishable by source.

- **Delete it (`delete`)** and prove it is gone:

  ```bash
  python3 $S req DELETE /api/v2/expenses/<id> --expect 204
  python3 $S req GET /api/v2/expenses/<id> --expect 404
  ```

  Observable: `204`, then `404` with `"detail": "Expense not found: <id>"`.

- **Prove the scoping (`scoped`)**:

  ```bash
  python3 $S req GET /api/v2/expenses/<id> --anon --expect 401
  ```

  Observable: `401` with no body. See [authorization.md](./authorization.md) for the cross-account
  half of that claim.

- **Sweep**:

  ```bash
  python3 $S sweep --run-id $RID
  ```

  Observable: `swept <RID>: … N expenses …`, matching on the description you stamped.

## Gotchas

- **`15075` means two different amounts depending on the path.** On `/api/v2/expenses` it is
  ₱150.75; on `/expenses` it is ₱15,075.00, and both validate. A money proof that does not name its
  surface is not a proof.
- **`DELETE /api/v2/expenses` with no id is `deleteAll` for the account.** It is a real route and it
  will wipe the seeded rows. Never reach for it as cleanup; use `sweep`.
- **`date` is optional and defaults to now, returned with `+08:00`.** An assertion built from the
  runner's local clock is a false failure waiting for a trip abroad.
- **`category` is matched by name, case-insensitively, and created on first use.** A typo does not
  fail — it silently makes a new category, which then shows up in the client's category list.
- **`source` is ignored on update.** It is recorded once, at creation; a `PUT` that sets it is not an
  error and changes nothing.
- **The Jackson-level `source` error advertises all five enum names**, including the three a client
  may not declare — so following its "allowed values" list produces the *other* `400`. Only `MANUAL`
  and `RECEIPT_SCAN` are declarable; the published schema narrows to those two and the error message
  does not.
- **Writes sit in a 60/minute bucket** (`WRITE_RATE_LIMIT_PER_MINUTE`), per JVM and in memory. A
  loop that creates rows to test paging can exhaust it and the `429` will look like the endpoint
  failing.
