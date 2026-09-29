# Goals

A savings goal is a named target amount with an amount already saved, an optional target date, and a
pause flag. The API derives `progressPercent` and a `status` from those numbers, which is what the
client's goal cards render. Amounts are integer centavos on v2; the percentage stays decimal.

## Sub-features

- `create` — `POST /api/v2/goals` with `name`, `targetAmount` and `savedAmount` answers `201` with
  `progressPercent` and `status` derived.
- `list` / `get` — `GET /api/v2/goals` and `GET /api/v2/goals/{id}` return this account's goals.
- `contribute` — `PUT /api/v2/goals/{id}` raising `savedAmount` moves `progressPercent`; there is no
  separate contribute route.
- `complete` — saving at or past `targetAmount` drives `progressPercent` to 100 and the `status` off
  `ON_TRACK`.
- `pause` — `paused: true` marks the goal paused without changing its amounts.
- `delete` — `DELETE /api/v2/goals/{id}` removes it; a later read is `404`.
- `validate` — `targetAmount` under 1, a negative `savedAmount`, or a blank `name` is `400`.

## How to get to it (user POV)

- Web: the Goals page — the create form, the contribute action on a card, the pause toggle and the
  delete action → `POST`, `PUT`, `DELETE /api/v2/goals`.
- Mobile: the goals screen, same v2 routes.
- Goals appear read-only on the dashboard; that view adds no endpoint of its own.

## Driving it with api.py

Preconditions: doctor passes; `S=.claude/skills/verify-gastosai-backend/api.py`;
`D=~/.claude/gastosai-backend-verify/$(date +%F)`; `RID=$(python3 $S runid)`; `python3 $S login`.

- **Create a goal (`create`)**:

  ```bash
  python3 $S req POST /api/v2/goals --expect 201 --save $D/goal-create.json \
      --json "{\"name\":\"Goal $RID\",\"targetAmount\":2000000,\"savedAmount\":500000,\"targetDate\":\"2026-12-31\"}"
  ```

  Observable: `201`, `"targetAmount": 2000000` (₱20,000.00), `"savedAmount": 500000`,
  `"progressPercent": 25.0`, `"status": "ON_TRACK"`, `"paused": false`, `"currency": "PHP"`, and a
  `createdAt` carrying `+08:00`. Verified on 2026-09-24 against build `0.94.3`. Keep the `id`.

- **Read it back (`get`)**:

  ```bash
  python3 $S req GET /api/v2/goals/<id> --expect 200
  ```

  Observable: the same numbers. The derived fields are computed on read, so this is where to check
  them, not only in the create response.

- **Contribute (`contribute`)** — a `PUT` carrying the new saved amount:

  ```bash
  python3 $S req PUT /api/v2/goals/<id> --expect 200 --save $D/goal-contribute.json \
      --json "{\"name\":\"Goal $RID\",\"targetAmount\":2000000,\"savedAmount\":1000000,\"targetDate\":\"2026-12-31\"}"
  ```

  Observable: `"savedAmount": 1000000` and `"progressPercent": 50.0`. The percentage is derived, so
  asserting it is what proves the contribution landed — not the `200`.

- **Complete it (`complete`)**:

  ```bash
  python3 $S req PUT /api/v2/goals/<id> --expect 200 \
      --json "{\"name\":\"Goal $RID\",\"targetAmount\":2000000,\"savedAmount\":2000000,\"targetDate\":\"2026-12-31\"}"
  ```

  Observable: `"progressPercent": 100.0` and a `status` that is no longer `ON_TRACK`. Record the
  exact value the build returns rather than assuming one; `GoalStatus` is derived from amount and
  date together.

- **Pause it (`pause`)**:

  ```bash
  python3 $S req PUT /api/v2/goals/<id> --expect 200 \
      --json "{\"name\":\"Goal $RID\",\"targetAmount\":2000000,\"savedAmount\":2000000,\"paused\":true}"
  ```

  Observable: `"paused": true`, amounts unchanged. Note the omitted `targetDate` — a `PUT` is a full
  replacement, so anything left out is cleared.

- **Reject a bad target (`validate`)**:

  ```bash
  python3 $S req POST /api/v2/goals --expect 400 \
      --json "{\"name\":\"Bad $RID\",\"targetAmount\":0,\"savedAmount\":0}"
  ```

  Observable: `400` naming `targetAmount`; `GoalRequestV2` bounds it at `@Min(1)` and `savedAmount`
  at `@Min(0)`.

- **Delete it (`delete`)**:

  ```bash
  python3 $S req DELETE /api/v2/goals/<id> --expect 204
  python3 $S req GET /api/v2/goals/<id> --expect 404
  ```

  Observable: `204`, then `404`.

- **Sweep**:

  ```bash
  python3 $S sweep --run-id $RID
  ```

  Observable: `N goals` in the counts, matched on the run id in the goal's name — which is why the
  name must carry it.

## Gotchas

- **`PUT` is a full replacement, not a patch.** Omitting `targetDate` or `currency` clears it. A
  "contribution" that loses the target date is a failed proof, not a passing one.
- **There is no contribute endpoint.** If a client ever grows one, this file is wrong — check the
  controller before reporting that a contribution is impossible.
- **`progressPercent` is decimal and `targetAmount`/`savedAmount` are centavos on v2.** `25.0` next
  to `2000000` is correct, not a unit bug. On v1 (`/goals`) the amounts are decimals instead.
- **`status` is derived from amount *and* target date**, so the same amounts can yield a different
  status as the date passes. Assert what the build returns and record the date.
- **Sweep matches the goal's `name`.** A goal created without the run id in its name survives the
  sweep and pollutes the next run's list.
