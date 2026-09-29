# Budgets

A budget is a per-category spending cap for one month. Clients create one against an existing
category, list the month's budgets, and read a summary that pairs each cap with what has actually
been spent — the numbers the dashboard's budget strip renders. Caps are integer centavos on v2, and
the month is a validated `YYYY-MM` string, never a date.

## Sub-features

- `create` — `POST /api/v2/budgets` with `categoryId`, `month` and `amountLimit` answers `201` and
  echoes the category's name.
- `list` — `GET /api/v2/budgets?month=YYYY-MM` returns the month's budgets; `month` is required.
- `summary` — `GET /api/v2/budgets/summary?month=YYYY-MM` returns per-category `budgeted`, `spent`,
  `remaining`, `percentUsed` and a `status`, plus `totalBudgeted`, `totalSpent`, `safeToSpend` and
  `dailyAllowance`.
- `spend-moves-summary` — an expense in a budgeted category raises that item's `spent` and can flip
  its `status` to `OVER_BUDGET`.
- `month-validate` — `2026-9` is `400`; the field is regex-bound, not parsed leniently.
- `update` / `delete` — `PUT` and `DELETE /api/v2/budgets/{id}` change and remove one cap; deleting a
  budget leaves its category behind.
- `category-fixture` — a budget needs a category id, so a clean proof creates its own category first
  rather than borrowing a seeded one whose numbers other rows move.

## How to get to it (user POV)

- Web: the Budgets page — the create form, the per-row edit and delete, and the month switcher →
  `POST`, `PUT`, `DELETE`, `GET /api/v2/budgets`.
- Web dashboard: the budget progress strip → `GET /api/v2/budgets/summary`.
- Mobile: the budgets tab, same v2 routes.
- Budget rules (`/api/v2/budget-rules`) are a separate surface that derives caps automatically — not
  this feature, not yet mapped.

## Driving it with api.py

Preconditions: doctor passes; `S=.claude/skills/verify-gastosai-backend/api.py`;
`D=~/.claude/gastosai-backend-verify/$(date +%F)`; `RID=$(python3 $S runid)`; `python3 $S login`;
`M=$(date +%Y-%m)`.

- **Create the fixture category (`category-fixture`)** — stamped, so the sweep can find it:

  ```bash
  python3 $S req POST /api/v2/categories --expect 201 \
      --json "{\"name\":\"Cat $RID\"}"
  ```

  Observable: `201` with an `id`. Keep it as `CID`.

- **Create the budget (`create`)**:

  ```bash
  python3 $S req POST /api/v2/budgets --expect 201 --save $D/budget-create.json \
      --json "{\"categoryId\":<CID>,\"month\":\"$M\",\"amountLimit\":500000}"
  ```

  Observable: `201`, `"amountLimit": 500000` (₱5,000.00), `"categoryName": "Cat <RID>"`,
  `"currency": "PHP"`, `"recurring": false`. Verified on 2026-09-24 against build `0.94.3`.

- **List the month (`list`)**:

  ```bash
  python3 $S req GET "/api/v2/budgets?month=$M" --expect 200 --max-body 800
  ```

  Observable: the new row among the seeded ones. `month` is a required parameter — omitting it is a
  `400`, not "all months".

- **Read the summary (`summary`)**:

  ```bash
  python3 $S req GET "/api/v2/budgets/summary?month=$M" --expect 200 --save $D/budget-summary.json
  ```

  Observable: an `items` entry for `<CID>` with `budgeted: 500000`, `spent: 0`, `remaining: 500000`,
  `percentUsed: 0` and a `status`. All money fields are centavos; `percentUsed` is a decimal, because
  a proportion is not money.

- **Spend against it (`spend-moves-summary`)** — the claim a client actually cares about:

  ```bash
  python3 $S req POST /api/v2/expenses --expect 201 \
      --json "{\"amount\":600000,\"description\":\"Over $RID\",\"category\":\"Cat $RID\"}"
  python3 $S req GET "/api/v2/budgets/summary?month=$M" --expect 200 --save $D/budget-summary-after.json
  ```

  Observable: the same item now reads `spent: 600000`, `remaining: -100000`, `percentUsed: 120`,
  `status: "OVER_BUDGET"`, and `totalSpent` has moved by `600000`. Compare the two saved summaries —
  a delta, never a total, since the seeded months carry their own budgets and spend.

- **Reject a malformed month (`month-validate`)**:

  ```bash
  python3 $S req POST /api/v2/budgets --expect 400 \
      --json "{\"categoryId\":<CID>,\"month\":\"2026-9\",\"amountLimit\":500000}"
  ```

  Observable: `400` with `"detail": "month: must be a valid month in YYYY-MM format"`.

- **Update and delete (`update`, `delete`)**:

  ```bash
  python3 $S req PUT /api/v2/budgets/<id> --expect 200 \
      --json "{\"categoryId\":<CID>,\"month\":\"$M\",\"amountLimit\":700000}"
  python3 $S req DELETE /api/v2/budgets/<id> --expect 204
  python3 $S req GET "/api/v2/budgets?month=$M" --expect 200
  ```

  Observable: the cap reads `700000` after the `PUT`, the row is absent after the `DELETE`, and
  `GET /api/v2/categories` still lists `Cat <RID>` — deleting a cap does not delete its category.

- **Sweep** — budgets need the month, or they are left behind:

  ```bash
  python3 $S sweep --run-id $RID --month $M
  ```

  Observable: counts for expenses, budgets and categories, matched on the run id in the description
  and the category name.

## Gotchas

- **`month` is required on `GET /api/v2/budgets` and on `deleteAll`.** There is no "every month"
  listing, and `DELETE /api/v2/budgets?month=...` removes every cap in that month for the account —
  never use it as cleanup.
- **`sweep` without `--month` silently skips budgets.** It matches on `categoryName`, which it can
  only see in a month's listing. A sweep that reports `0 budgets` when you created one usually means
  the flag was missing, not that the delete failed.
- **A budget is keyed by category and month**, so creating a second one for the same pair is not a
  second budget. Use a fresh stamped category per run rather than a seeded one.
- **`amountLimit` is centavos on v2 and a decimal on v1** (`/budgets`). `500000` is ₱5,000.00 here
  and ₱500,000.00 there.
- **Deleting the fixture category while a budget still points at it** is the wrong sweep order;
  `sweep` deletes budgets before categories for that reason.
- **The summary's `spent` counts every expense in the category for that month**, including seeded
  rows. An assertion on an absolute `spent` will drift; assert the delta your expense produced.
