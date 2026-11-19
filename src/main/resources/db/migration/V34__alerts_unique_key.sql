-- TEN-551. One alert per key, enforced by the schema. Step: expand (two new indexes; no column is
-- renamed, dropped or retyped). It deletes duplicate rows, so the pre-migration backup
-- (scripts/backup-before-migrate.sh) must run first, as for any migration.
--
-- AlertService looks an alert up by its key before creating it, with finders that return
-- Optional. Two overlapping GET /api/v2/alerts requests both found nothing and both inserted,
-- and from then on every lookup of that key threw IncorrectResultSizeDataAccessException, which
-- failed the whole alerts read.
--
-- There are two keys, because RECURRING_DUE alerts are one per recurring expense, not one per
-- category name: several recurring expenses can share a category, and their alerts then share
-- category_name legitimately.
--
--   * category key  (user_id, type, alert_month, category_name)        every type but RECURRING_DUE
--   * recurring key (user_id, type, alert_month, recurring_expense_id) when the id is set
--
-- The category key excludes RECURRING_DUE by type, not by "recurring_expense_id IS NULL": that
-- column is ON DELETE SET NULL, so deleting two recurring expenses in one category would null both
-- of their alerts into the category key, and the delete itself would fail on the index. An
-- orphaned RECURRING_DUE alert is in neither key, which is correct: nothing creates or looks up an
-- alert by a NULL recurring id.

-- 1. Remove existing duplicates, keeping the oldest row of each key (created_at, then id).
DELETE FROM alerts a
USING (
    SELECT id,
           row_number() OVER (PARTITION BY user_id, type, alert_month, category_name
                              ORDER BY created_at, id) AS rn
    FROM alerts
    WHERE type <> 'RECURRING_DUE'
) d
WHERE a.id = d.id AND d.rn > 1;

DELETE FROM alerts a
USING (
    SELECT id,
           row_number() OVER (PARTITION BY user_id, type, alert_month, recurring_expense_id
                              ORDER BY created_at, id) AS rn
    FROM alerts
    WHERE recurring_expense_id IS NOT NULL
) d
WHERE a.id = d.id AND d.rn > 1;

-- 2. Guarantee it from now on.
CREATE UNIQUE INDEX ux_alerts_category_key
    ON alerts (user_id, type, alert_month, category_name)
    WHERE type <> 'RECURRING_DUE';

CREATE UNIQUE INDEX ux_alerts_recurring_key
    ON alerts (user_id, type, alert_month, recurring_expense_id)
    WHERE recurring_expense_id IS NOT NULL;
