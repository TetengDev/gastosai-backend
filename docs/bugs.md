# Bug log — gastosai-backend

Every bug whose fault is in this repository, in the order it was found. Linear holds the work;
this file is the record of what went wrong, so a pattern stays visible after the issues close.

**Log a bug where its cause is, not where it was seen.** A bug spotted in the web app whose fault
is in the API goes in the backend's log, with "Found via" saying where it showed up. A bug with
faults in two repositories gets a row in each.

**Add a row when a bug is found**, even one fixed on the spot. Newest at the bottom. When the fix
merges, set **Status** to the PR that fixed it. A bug fixed before it was ever filed gets the
fixing commit or PR in place of an issue.

| Column | Contents |
|---|---|
| Found | Date, `YYYY-MM-DD` |
| Found via | Where it showed up: a local run, a review, CI, a test suite |
| Symptom | What a user or a run actually saw |
| Cause | Where the fault is, if known |
| Issue | The Linear issue, linked |
| Status | `open`, `fixed in <PR or commit>`, or `won't fix: <why>` |

## Product

| Found | Found via | Symptom | Cause | Issue | Status |
|---|---|---|---|---|---|
| 2026-10-13 | Web chat, against the local stack | "add lunch 70 pesos for meal plan now", confirmed, is stored today at 12:00 AM instead of the confirm time | `ChatActionService` turns the model's date-only `date` into `LocalDate.parse(...).atStartOfDay()`; the update path does the same | [TEN-456](https://linear.app/tengdev/issue/TEN-456) | fixed in [#134](https://github.com/TetengDev/gastosai-backend/pull/134) |
| 2026-10-13 | Review of [#134](https://github.com/TetengDev/gastosai-backend/pull/134) | On a server running in UTC, an expense created with no date (`POST /expenses`, quick-add) in the first eight hours of a Manila day is stored on the previous day, and counted in the wrong day and month totals | `ExpenseService.create` falls back to `LocalDateTime.now()`, the JVM's zone, while expense columns hold Manila wall-clock time | [TEN-457](https://linear.app/tengdev/issue/TEN-457) | fixed in [#135](https://github.com/TetengDev/gastosai-backend/pull/135) |
| 2026-10-16 | Pre-PR gate on [#135](https://github.com/TetengDev/gastosai-backend/pull/135) | On a server running in UTC, a CSV import row with a blank or unparseable date, imported in the first eight hours of a Manila day, is stored on the previous day | `CsvImportService.parseDate` falls back to `LocalDateTime.now()`, the JVM's zone | [TEN-468](https://linear.app/tengdev/issue/TEN-468) | open |
| 2026-10-22 | Tracing the mobile dashboard Maestro full-suite failure ([TEN-461](https://linear.app/tengdev/issue/TEN-461)) | The 21st `GET /ai/insights/top-category` inside a minute returns 429, though the endpoint makes no model call | `AiRateLimitInterceptor` covers every `/ai/**` path; `top-category` is a category report read. Cached `month-summary`/`recommendations` still spend a token because the interceptor runs before the cache (not changed) | [TEN-464](https://linear.app/tengdev/issue/TEN-464) | fixed in [#138](https://github.com/TetengDev/gastosai-backend/pull/138) for top-category; cached insights still charged, [TEN-483](https://linear.app/tengdev/issue/TEN-483) |
| 2026-10-23 | Review of [TEN-464](https://linear.app/tengdev/issue/TEN-464) ([#138](https://github.com/TetengDev/gastosai-backend/pull/138)) | A cached `GET /ai/insights/month-summary` or `/ai/insights/recommendations` answer spends an `/ai/**` token on every read, hit or miss, so the 21st read in a minute can return 429 though no model call was made | `AiRateLimitInterceptor` runs before `@Cacheable`, so it cannot tell a cache hit from a model call | [TEN-483](https://linear.app/tengdev/issue/TEN-483) | fixed in [#139](https://github.com/TetengDev/gastosai-backend/pull/139) |
